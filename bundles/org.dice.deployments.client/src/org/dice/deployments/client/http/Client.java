package org.dice.deployments.client.http;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.SSLContext;

import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContexts;
import org.dice.deployments.client.exception.ClientError;
import org.dice.deployments.client.model.Blueprint;
import org.dice.deployments.client.model.Container;
import org.dice.deployments.client.model.Message;
import org.dice.deployments.client.model.Token;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class Client {

  private static final int CONNECTION_TIMEOUT_MS = 5000;
  private static final int SOCKET_TIMEOUT_MS = 60000;

  private String token;
  private CloseableHttpClient http;
  private URI address;
  private Gson parser;

  public Client(URI baseAddress, int connectionTimeout, int socketTimeout,
      String keystoreFile, String keystorePass) throws ClientError {
    final RequestConfig config =
        RequestConfig.custom().setConnectTimeout(connectionTimeout)
            .setConnectionRequestTimeout(connectionTimeout)
            .setSocketTimeout(socketTimeout).build();

    HttpClientBuilder builder =
        HttpClients.custom().setDefaultRequestConfig(config);

    if (keystoreFile != null && !keystoreFile.equals("")) {
      try {
        KeyStore tks = KeyStore.getInstance(KeyStore.getDefaultType());
        tks.load(new FileInputStream(keystoreFile),
            keystorePass.toCharArray());
        SSLContext sslCtx = SSLContexts.custom()
            .loadTrustMaterial(tks, new TrustSelfSignedStrategy()).build();
        SSLConnectionSocketFactory csf = new SSLConnectionSocketFactory(sslCtx,
            new String[] {"TLSv1"}, null, new DefaultHostnameVerifier());
        builder.setSSLSocketFactory(csf);
      } catch (NoSuchAlgorithmException | CertificateException
          | KeyStoreException | KeyManagementException | IOException e) {
        throw new ClientError(e.getMessage());
      }
    }

    http = builder.build();
    token = null;
    address = baseAddress;
    parser = new GsonBuilder().serializeNulls()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .excludeFieldsWithoutExposeAnnotation().create();
  }

  public Client(URI baseAddress, String keystoreFile, String keystorePass)
      throws ClientError {
    this(baseAddress, CONNECTION_TIMEOUT_MS, SOCKET_TIMEOUT_MS, keystoreFile,
        keystorePass);
  }

  public boolean authenticate(String username, String password)
      throws ClientError {
    Map<String, String> data = new HashMap<>();
    data.put("username", username);
    data.put("password", password);

    Response response = post("/auth/get-token", null, data);
    if (response.statusCode != HttpStatus.SC_OK) {
      return false;
    }
    token = parser.fromJson(response.asString(), Token.class).getToken();
    return true;
  }

  public void close() {
    try {
      http.close();
    } catch (IOException e) {
      // Really cannot do much here, but since we are already tearing the
      // connection down, it is relatively safe to assume that ignoring this
      // will cause no harm.
    }
  }

  // Heartbeat
  public boolean getHeartbeat() throws ClientError {
    Response response = get("/heartbeat", null);
    return response.statusCode == HttpStatus.SC_OK;
  }

  // Container methods
  public Result<Container[], Message> listContainers() throws ClientError {
    Response response = get("/containers", null);
    return getResult(response, Container[].class, HttpStatus.SC_OK);
  }

  public Result<Container, Message> getContainer(String id)
      throws ClientError {
    Response response = get("/containers/" + id, null);
    return getResult(response, Container.class, HttpStatus.SC_OK);
  }

  public Result<Blueprint, Message> deployBlueprint(String containerId,
      File blueprint, Map<String, String> metadata, boolean register)
      throws ClientError {
    String path = String.format("/containers/%s/blueprint", containerId);
    Map<String, String> params = null;
    if (register) {
      params = new HashMap<>();
      params.put("register_app", "true");
    }
    Response response = post(path, params, metadata, blueprint);
    return getResult(response, Blueprint.class, HttpStatus.SC_ACCEPTED);
  }

  public Result<Blueprint, Message> undeployBlueprint(String containerId)
      throws ClientError {
    String path = String.format("/containers/%s/blueprint", containerId);
    Response response = delete(path, null);
    return getResult(response, Blueprint.class, HttpStatus.SC_ACCEPTED);
  }

  // Private API
  private <T> Result<T, Message> getResult(Response resp, Class<T> klass,
      int status) {
    if (resp == null) {
      return new Result<>(null, new Message("Service is not accessible"));
    }
    return resp.statusCode != status ? error(resp) : ok(resp, klass);
  }

  private <T> Result<T, Message> error(Response response) {
    return new Result<>(null,
        parser.fromJson(response.asString(), Message.class));
  }

  private <T> Result<T, Message> ok(Response response, Class<T> klass) {
    return new Result<>(parser.fromJson(response.asString(), klass), null);
  }

  private URI buildURI(String path, Map<String, String> params)
      throws ClientError {
    URIBuilder builder = new URIBuilder(address);
    builder.setPath(builder.getPath() + path);
    if (params != null) {
      for (Map.Entry<String, String> entry : params.entrySet()) {
        builder.addParameter(entry.getKey(), entry.getValue());
      }
    }
    try {
      return builder.build();
    } catch (URISyntaxException e) {
      throw new ClientError(String.format("Bad path: %d", path));
    }
  }

  private void addAuth(RequestBuilder builder) {
    if (token != null) {
      builder.addHeader("Authorization", "Token " + token);
    }
  }

  private Response execute(RequestBuilder builder) throws ClientError {
    Response response = null;
    try {
      response = new Response(http.execute(builder.build()));
    } catch (IOException e) {
      throw new ClientError(e.getMessage());
    }
    return response;
  }

  private Response get(String path, Map<String, String> params)
      throws ClientError {
    RequestBuilder builder =
        RequestBuilder.get().setUri(buildURI(path, params));
    addAuth(builder);
    return execute(builder);
  }

  private Response post(String path, Map<String, String> params,
      Map<String, String> data) throws ClientError {
    RequestBuilder builder =
        RequestBuilder.post().setUri(buildURI(path, params));
    if (data != null) {
      for (Map.Entry<String, String> entry : data.entrySet()) {
        builder.addParameter(entry.getKey(), entry.getValue());
      }
    }
    addAuth(builder);
    return execute(builder);
  }

  private Response post(String path, Map<String, String> params,
      Map<String, String> data, File file) throws ClientError {
    RequestBuilder builder =
        RequestBuilder.post().setUri(buildURI(path, params));
    addAuth(builder);
    MultipartEntityBuilder payload = MultipartEntityBuilder.create();
    payload.addBinaryBody("file", file);
    if (data != null) {
      for (Map.Entry<String, String> entry : data.entrySet()) {
        payload.addTextBody(entry.getKey(), entry.getValue());
      }
    }
    builder.setEntity(payload.build());
    return execute(builder);
  }

  private Response delete(String path, Map<String, String> params)
      throws ClientError {
    RequestBuilder builder =
        RequestBuilder.delete().setUri(buildURI(path, params));
    addAuth(builder);
    return execute(builder);
  }

}
