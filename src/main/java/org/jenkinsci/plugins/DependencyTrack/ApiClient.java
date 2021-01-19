/*
 * This file is part of Dependency-Track Jenkins plugin.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jenkinsci.plugins.DependencyTrack;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.FilePath;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.DependencyTrack.model.Finding;
import org.jenkinsci.plugins.DependencyTrack.model.Project;
import org.jenkinsci.plugins.DependencyTrack.model.UploadResult;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;

@RequiredArgsConstructor
public class ApiClient {

    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String HEADER_ACCEPT = "Accept";
    private static final String MEDIATYPE_JSON = "application/json";
    private static final String API_URL = "/api/v1";
    private static final int MS_TO_S_FACTOR = 1000;
    static final String API_KEY_HEADER = "X-Api-Key";
    static final String PROJECT_FINDINGS_URL = API_URL + "/finding/project";
    static final String BOM_URL = API_URL + "/bom";
    static final String BOM_TOKEN_URL = BOM_URL + "/token";
    static final String PROJECT_URL = API_URL + "/project";
    static final String PROJECT_LOOKUP_URL = PROJECT_URL + "/lookup";
    static final String PROJECT_LOOKUP_NAME_PARAM = "name";
    static final String PROJECT_LOOKUP_VERSION_PARAM = "version";

    /**
     * the base url to DT instance without trailing slashes, e.g.
     * "http://host.tld:port"
     */
    private final String baseApiUrl;

    /**
     * the api key to authorize with against DT
     */
    private final String apiKey;

    private final ConsoleLogger logger;

    /**
     * the connection-timeout in seconds for every call to DT
     */
    private final int connectionTimeout;

    /**
     * the read-timeout in seconds for every call to DT
     */
    private final int readTimeout;

    @NonNull
    public String testConnection() throws ApiClientException {
        try {
            final HttpURLConnection conn = (HttpURLConnection) new URL(baseApiUrl + PROJECT_URL).openConnection();
            conn.setRequestProperty(HEADER_ACCEPT, MEDIATYPE_JSON);
            conn.setRequestProperty(API_KEY_HEADER, apiKey);
            conn.setConnectTimeout(connectionTimeout * MS_TO_S_FACTOR);
            conn.setReadTimeout(readTimeout * MS_TO_S_FACTOR);
            conn.connect();
            if (conn.getResponseCode() == HTTP_OK) {
                return StringUtils.trimToEmpty(conn.getHeaderField("X-Powered-By"));
            } else {
                logHttpError(conn);
                throw new ApiClientException(Messages.ApiClient_Error_Connection(conn.getResponseCode(), conn.getResponseMessage()));
            }
        } catch (ApiClientException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiClientException(Messages.ApiClient_Error_Connection(StringUtils.EMPTY, StringUtils.EMPTY), e);
        }
    }

    @NonNull
    public List<Project> getProjects() throws ApiClientException {
        List<Project> projects = new ArrayList<>();
        int page = 1;
        boolean fetchMore = true;
        while (fetchMore) {
            List<Project> fetchedProjects = getProjectsPaged(page++);
            fetchMore = !fetchedProjects.isEmpty();
            projects.addAll(fetchedProjects);
        }
        return projects;
    }

    @NonNull
    private List<Project> getProjectsPaged(int page) throws ApiClientException {
        try {
            final HttpURLConnection conn = (HttpURLConnection) new URL(baseApiUrl + PROJECT_URL + "?limit=500&excludeInactive=true&page=" + page).openConnection();
            conn.setRequestProperty(HEADER_ACCEPT, MEDIATYPE_JSON);
            conn.setRequestProperty(API_KEY_HEADER, apiKey);
            conn.setConnectTimeout(connectionTimeout * MS_TO_S_FACTOR);
            conn.setReadTimeout(readTimeout * MS_TO_S_FACTOR);
            conn.setDoOutput(true);
            conn.connect();
            if (conn.getResponseCode() == HTTP_OK) {
                try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
                    JSONArray array = JSONArray.fromObject(getResponseBody(in));
                    return array.stream()
                            .map(o -> ProjectParser.parse((JSONObject) o))
                            .collect(Collectors.toList());
                }
            }
        } catch (IOException e) {
            throw new ApiClientException(Messages.ApiClient_Error_Connection(StringUtils.EMPTY, StringUtils.EMPTY), e);
        }
        return Collections.emptyList();
    }

    @NonNull
    public Project lookupProject(String projectName, String projectVersion) throws ApiClientException {
        try {
            final HttpURLConnection conn = (HttpURLConnection) new URL(baseApiUrl + PROJECT_LOOKUP_URL + "?"
                    + PROJECT_LOOKUP_NAME_PARAM + "=" + URLEncoder.encode(projectName, StandardCharsets.UTF_8.name()) + "&"
                    + PROJECT_LOOKUP_VERSION_PARAM + "=" + URLEncoder.encode(projectVersion, StandardCharsets.UTF_8.name()))
                    .openConnection();
            conn.setDoOutput(true);
            conn.setRequestProperty(API_KEY_HEADER, apiKey);
            conn.setRequestProperty(HEADER_ACCEPT, MEDIATYPE_JSON);
            conn.setConnectTimeout(connectionTimeout * MS_TO_S_FACTOR);
            conn.setReadTimeout(readTimeout * MS_TO_S_FACTOR);
            conn.connect();
            // Checks the server response
            if (conn.getResponseCode() == HTTP_OK) {
                try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
                    final JSONObject jsonObject = JSONObject.fromObject(getResponseBody(in));
                    final String version = jsonObject.getString("version");
                    final Project.ProjectBuilder builder = Project.builder()
                            .name(jsonObject.getString("name"))
                            .uuid(jsonObject.getString("uuid"));
                    if (StringUtils.isNotBlank(version) && !"null".equalsIgnoreCase(version)) {
                        builder.version(version);
                    }
                    return builder.build();
                }
            } else {
                logHttpError(conn);
                throw new ApiClientException(Messages.ApiClient_Error_ProjectLookup(projectName, projectVersion, conn.getResponseCode(), conn.getResponseMessage()));
            }
        } catch (ApiClientException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiClientException(Messages.ApiClient_Error_ProjectLookup(projectName, projectVersion, StringUtils.EMPTY, StringUtils.EMPTY), e);
        }
    }

    @NonNull
    public List<Finding> getFindings(String projectUuid) throws ApiClientException {
        try {
            final HttpURLConnection conn = (HttpURLConnection) new URL(baseApiUrl + PROJECT_FINDINGS_URL + "/" + URLEncoder.encode(projectUuid, StandardCharsets.UTF_8.name()))
                    .openConnection();
            conn.setDoOutput(true);
            conn.setRequestProperty(API_KEY_HEADER, apiKey);
            conn.setRequestProperty(HEADER_ACCEPT, MEDIATYPE_JSON);
            conn.setConnectTimeout(connectionTimeout * MS_TO_S_FACTOR);
            conn.setReadTimeout(readTimeout * MS_TO_S_FACTOR);
            conn.connect();
            // Checks the server response
            if (conn.getResponseCode() == HTTP_OK) {
                try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
                    return FindingParser.parse(getResponseBody(in));
                }
            } else {
                logHttpError(conn);
                throw new ApiClientException(Messages.ApiClient_Error_RetrieveFindings(conn.getResponseCode(), conn.getResponseMessage()));
            }
        } catch (ApiClientException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiClientException(Messages.ApiClient_Error_RetrieveFindings(StringUtils.EMPTY, StringUtils.EMPTY), e);
        }
    }

    @NonNull
    public UploadResult upload(String projectId, String projectName, String projectVersion, FilePath artifact,
            boolean autoCreateProject) throws IOException {
        final String encodedScan;
        try {
            encodedScan = Base64.encodeBase64String(artifact.readToString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            logger.log(Messages.Builder_Error_Processing(artifact.getRemote(), e.getLocalizedMessage()));
            return new UploadResult(false);
        }
        // Creates the JSON payload that will be sent to Dependency-Track
        JSONObject jsonObject = new JSONObject();
        jsonObject.element("bom", encodedScan);
        if (projectId != null) {
            jsonObject.element("project", projectId);
        } else {
            jsonObject.element("projectName", projectName)
                    .element("projectVersion", projectVersion)
                    .element("autoCreate", autoCreateProject);
        }
        byte[] payloadBytes = jsonObject.toString().getBytes(StandardCharsets.UTF_8);
        // Creates the request and connects
        final HttpURLConnection conn = (HttpURLConnection) new URL(baseApiUrl + BOM_URL).openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("PUT");
        conn.setRequestProperty(HEADER_CONTENT_TYPE, MEDIATYPE_JSON);
        conn.setRequestProperty(HEADER_ACCEPT, MEDIATYPE_JSON);
        conn.setConnectTimeout(connectionTimeout * MS_TO_S_FACTOR);
        conn.setReadTimeout(readTimeout * MS_TO_S_FACTOR);
        conn.setRequestProperty("Content-Length", Integer.toString(payloadBytes.length));
        conn.setRequestProperty(API_KEY_HEADER, apiKey);
        conn.connect();
        // Sends the payload bytes
        try (OutputStream os = new BufferedOutputStream(conn.getOutputStream())) {
            os.write(payloadBytes);
            os.flush();
        } catch (IOException e) {
            logger.log(Messages.Builder_Error_Processing(artifact.getRemote(), e.getLocalizedMessage()));
            return new UploadResult(false);
        }
        // Checks the server response
        switch (conn.getResponseCode()) {
            case HTTP_OK:
                try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
                    String responseBody = getResponseBody(in);
                    if (StringUtils.isNotBlank(responseBody)) {
                        final JSONObject json = JSONObject.fromObject(responseBody);
                        return new UploadResult(true, StringUtils.trimToNull(json.getString("token")));
                    } else {
                        return new UploadResult(true);
                    }
                }
            case HTTP_BAD_REQUEST:
                logger.log(Messages.Builder_Payload_Invalid());
                logHttpError(conn);
                break;
            case HTTP_UNAUTHORIZED:
                logger.log(Messages.Builder_Unauthorized());
                logHttpError(conn);
                break;
            case HTTP_NOT_FOUND:
                logger.log(Messages.Builder_Project_NotFound());
                logHttpError(conn);
                break;
            default:
                logger.log(Messages.ApiClient_Error_Connection(conn.getResponseCode(), conn.getResponseMessage()));
                logHttpError(conn);
                break;
        }
        return new UploadResult(false);
    }

    @NonNull
    public boolean isTokenBeingProcessed(String token) throws ApiClientException {
        try {
            final HttpURLConnection conn = (HttpURLConnection) new URL(baseApiUrl + BOM_TOKEN_URL + "/" + URLEncoder.encode(token, StandardCharsets.UTF_8.name()))
                    .openConnection();
            conn.setDoOutput(true);
            conn.setRequestProperty(API_KEY_HEADER, apiKey);
            conn.setRequestProperty(HEADER_ACCEPT, MEDIATYPE_JSON);
            conn.setConnectTimeout(connectionTimeout * MS_TO_S_FACTOR);
            conn.setReadTimeout(readTimeout * MS_TO_S_FACTOR);
            conn.connect();
            if (conn.getResponseCode() == HTTP_OK) {
                try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
                    final JSONObject jsonObject = JSONObject.fromObject(getResponseBody(in));
                    return jsonObject.getBoolean("processing");
                }
            } else {
                logger.log(Messages.ApiClient_Error_TokenProcessing(conn.getResponseCode(), conn.getResponseMessage()));
                logHttpError(conn);
                throw new ApiClientException(Messages.ApiClient_Error_TokenProcessing(conn.getResponseCode(), conn.getResponseMessage()));
            }
        } catch (ApiClientException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiClientException(Messages.ApiClient_Error_TokenProcessing(StringUtils.EMPTY, StringUtils.EMPTY), e);
        }
    }

    private String getResponseBody(InputStream in) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        return reader.lines().collect(Collectors.joining());
    }

    private void logHttpError(final HttpURLConnection conn) {
        try (InputStream in = new BufferedInputStream(conn.getErrorStream())) {
            logger.log(getResponseBody(in));
        } catch (UncheckedIOException | IOException ignore) {
            // ignored ... the error stream might have been closed already or whatever
        }
    }
}
