/*
 * Copyright © WebServices pour l'Éducation, 2014
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

package fr.wseduc.webutils.http.oauth;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.Map;

import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.http.HttpClient;
import org.vertx.java.core.http.HttpClientRequest;
import org.vertx.java.core.http.HttpClientResponse;
import org.vertx.java.core.http.HttpServerRequest;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.json.impl.Base64;

import fr.wseduc.webutils.http.Renders;

public class OAuth2Client {

	private final URI uri;
	private final String clientId;
	private final String secret;
	private final String authorizeUrn;
	private final String tokenUrn;
	private final String redirectUri;
	private final HttpClient httpClient;

	public OAuth2Client(URI uri, String clientId, String secret, String authorizeUrn,
			String tokenUrn, String redirectUri, Vertx vertx, int poolSize) {
		this.httpClient = vertx.createHttpClient()
				.setHost(uri.getHost())
				.setPort(uri.getPort())
				.setSSL("https".equals(uri.getScheme()))
				.setMaxPoolSize(poolSize)
				.setKeepAlive(false);
		this.uri = uri;
		this.clientId = clientId;
		this.secret = secret;
		this.authorizeUrn = authorizeUrn;
		this.tokenUrn = tokenUrn;
		this.redirectUri = redirectUri;
	}

	public OAuth2Client(URI uri, String clientId, String secret, String redirectUri, Vertx vertx) {
		this(uri, clientId, secret, "/oauth2/auth", "/oauth2/token", redirectUri, vertx, 16);
	}

	public void authorizeRedirect(HttpServerRequest request, String state, String scope) {
		String rUri = "";
		try {
			rUri = URLEncoder.encode(redirectUri, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		String params = "response_type=code&client_id=" + clientId + "&state=" + state +
				"&redirect_uri=" + rUri + "&scope=" + scope;

		Renders.redirect(request, uri.toString(), authorizeUrn + "?" + params);
	}

	public void authorizationCodeToken(HttpServerRequest request, String state,
			Handler<JsonObject> handler) {
		authorizationCodeToken(request, state, true, handler);
	}

	public void authorizationCodeToken(HttpServerRequest request, String state, boolean basic,
			Handler<JsonObject> handler) {
		String s = request.params().get("state");
		String code = request.params().get("code");
		String error = request.params().get("error");
		JsonObject r = new JsonObject();
		if (state != null && !state.equals(s)) {
			handler.handle(r.putString("error", "invalid_state"));
			return;
		}
		if (error != null) {
			handler.handle(r.putString("error", error));
			return;
		}
		try {
			getAccessToken(code, basic, handler);
		} catch (UnsupportedEncodingException e) {
			handler.handle(r.putString("error", e.getMessage()));
		}
	}

	public void getAccessToken(String code,
			final Handler<JsonObject> handler) throws UnsupportedEncodingException {
		getAccessToken(code, true, handler);
	}

	public void getAccessToken(String code, boolean basic,
			final Handler<JsonObject> handler) throws UnsupportedEncodingException {
		HttpClientRequest req = httpClient.post(tokenUrn, new Handler<HttpClientResponse>() {

			@Override
			public void handle(final HttpClientResponse response) {
				response.bodyHandler(new Handler<Buffer>() {

					@Override
					public void handle(Buffer r) {
						JsonObject j = new JsonObject(r.toString("UTF-8"));
						if (response.statusCode() == 200) {
							JsonObject json = new JsonObject()
							.putString("status", "ok")
							.putObject("token", j);
							handler.handle(json);
						} else {
							handler.handle(j.putNumber("statusCode", response.statusCode()));
						}
					}
				});
			}
		});
		String body = "grant_type=authorization_code&code=" + code +
				"&redirect_uri=" + redirectUri;
		if (basic) {
		req.headers()
			.add("Authorization", "Basic " + Base64.encodeBytes(
					(clientId + ":" + secret).getBytes("UTF-8")));
		} else {
			body += "&client_id=" + clientId + "&client_secret=" + secret;
		}
		req.headers()
			.add("Content-Type", "application/x-www-form-urlencoded")
			.add("Accept", "application/json; charset=UTF-8");
		req.end(body, "UTF-8");
	}

	public void clientCredentialsToken(String scope,
			final Handler<JsonObject> handler) throws UnsupportedEncodingException {
		HttpClientRequest req = httpClient.post(tokenUrn, new Handler<HttpClientResponse>() {

			@Override
			public void handle(final HttpClientResponse response) {
				response.bodyHandler(new Handler<Buffer>() {

					@Override
					public void handle(Buffer r) {
						JsonObject j = new JsonObject(r.toString("UTF-8"));
						if (response.statusCode() == 200) {
							JsonObject json = new JsonObject()
									.putString("status", "ok")
									.putObject("token", j);
							handler.handle(json);
						} else {
							handler.handle(j.putNumber("statusCode", response.statusCode()));
						}
					}
				});
			}
		});
		req.headers()
				.add("Authorization", "Basic " + Base64.encodeBytes(
						(clientId + ":" + secret).getBytes("UTF-8")))
				.add("Content-Type", "application/x-www-form-urlencoded")
				.add("Accept", "application/json; charset=UTF-8");
		String body = "grant_type=client_credentials";
		if (scope != null && !scope.trim().isEmpty()) {
			body += "&scope=" + scope;
		}
		req.end(body, "UTF-8");
	}

	public void getProtectedResource(String path, String accessToken,
			Handler<HttpClientResponse> handler) {
		getProtectedResource(path, accessToken, "application/json; charset=UTF-8", handler);
	}

	public void getProtectedResource(String path, String accessToken, Map<String, String> headers,
			Handler<HttpClientResponse> handler) {
		sendProtectedResource(accessToken, headers, null, httpClient.get(path, handler));
	}

	public void getProtectedResource(String path, String accessToken, String acceptMimeType,
			Handler<HttpClientResponse> handler) {
		sendProtectedResource(accessToken, acceptMimeType, httpClient.get(path, handler));
	}

	public void postProtectedResource(String path, String accessToken, String body,
									 Handler<HttpClientResponse> handler) {
		postProtectedResource(path, accessToken, "application/json; charset=UTF-8", body, handler);
	}

	public void postProtectedResource(String path, String accessToken, Map<String, String> headers,
									  String body, Handler<HttpClientResponse> handler) {
		sendProtectedResource(accessToken, headers, body, httpClient.post(path, handler));
	}

	public void postProtectedResource(String path, String accessToken, String acceptMimeType,
			String body, Handler<HttpClientResponse> handler) {
		sendProtectedResource(accessToken, acceptMimeType, httpClient.post(path, handler), body, null);
	}

	public void putProtectedResource(String path, String accessToken, String body,
									  Handler<HttpClientResponse> handler) {
		putProtectedResource(path, accessToken, "application/json; charset=UTF-8", body, handler);
	}

	public void putProtectedResource(String path, String accessToken, Map<String, String> headers,
									 String body, Handler<HttpClientResponse> handler) {
		sendProtectedResource(accessToken, headers, body, httpClient.put(path, handler));
	}

	public void putProtectedResource(String path, String accessToken, String acceptMimeType,
			String body, Handler<HttpClientResponse> handler) {
		sendProtectedResource(accessToken, acceptMimeType, httpClient.put(path, handler), body, null);
	}

	public void deleteProtectedResource(String path, String accessToken,
									  Handler<HttpClientResponse> handler) {
		deleteProtectedResource(path, accessToken, "application/json; charset=UTF-8", handler);
	}

	public void deleteProtectedResource(String path, String accessToken, Map<String, String> headers,
										Handler<HttpClientResponse> handler) {
		sendProtectedResource(accessToken, headers, null, httpClient.delete(path, handler));
	}

	public void deleteProtectedResource(String path, String accessToken, String acceptMimeType,
									  Handler<HttpClientResponse> handler) {
		sendProtectedResource(accessToken, acceptMimeType, httpClient.delete(path, handler));
	}

	private void sendProtectedResource(String accessToken, Map<String, String> headers, String body,
			HttpClientRequest req) {
		String acceptMimeType = "application/json; charset=UTF-8";
		if (headers != null && headers.containsKey("Accept")) {
			acceptMimeType = headers.remove("Accept");
		}
		sendProtectedResource(accessToken, acceptMimeType, req, body, headers);
	}

	private void sendProtectedResource(String accessToken, String acceptMimeType,
									   HttpClientRequest req) {
		sendProtectedResource(accessToken, acceptMimeType, req, null, null);
	}

	private void sendProtectedResource(String accessToken, String acceptMimeType,
			HttpClientRequest req, String body, Map<String, String> customHeaders) {
		req.headers()
				.add("Authorization", "Bearer " + accessToken)
				.add("Accept", acceptMimeType);
		if (customHeaders != null) {
			req.headers().add(customHeaders);
		}
		if (body != null) {
			req.end(body);
		} else {
			req.end();
		}
	}

	public void close() {
		if (httpClient != null) {
			httpClient.close();
		}
	}

}
