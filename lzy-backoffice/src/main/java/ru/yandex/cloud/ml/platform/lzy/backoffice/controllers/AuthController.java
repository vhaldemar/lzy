package ru.yandex.cloud.ml.platform.lzy.backoffice.controllers;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.server.util.HttpHostResolver;
import io.micronaut.http.uri.UriBuilder;
import io.micronaut.web.router.RouteBuilder;
import jakarta.inject.Inject;
import ru.yandex.cloud.ml.platform.lzy.backoffice.grpc.Client;
import ru.yandex.cloud.ml.platform.lzy.backoffice.models.*;
import ru.yandex.cloud.ml.platform.lzy.backoffice.oauth.OAuthConfig;
import ru.yandex.cloud.ml.platform.lzy.backoffice.oauth.models.GitHubGetUserResponse;
import ru.yandex.cloud.ml.platform.lzy.backoffice.oauth.models.GithubAccessTokenRequest;
import ru.yandex.cloud.ml.platform.lzy.backoffice.oauth.models.GithubAccessTokenResponse;
import ru.yandex.cloud.ml.platform.lzy.model.utils.AuthProviders;
import yandex.cloud.priv.datasphere.v2.lzy.BackOffice;

import javax.validation.Valid;
import java.net.URI;
import java.net.URISyntaxException;

@Controller("auth")
public class AuthController {
    @Inject
    Client client;

    @Inject
    OAuthConfig oAuthConfig;

    @Inject
    private HttpHostResolver httpHostResolver;

    @Inject
    private RouteBuilder.UriNamingStrategy uriNamingStrategy;

    @io.micronaut.http.client.annotation.Client("https://github.com")
    @Inject
    private HttpClient githubClient;

    @io.micronaut.http.client.annotation.Client("https://api.github.com")
    @Inject
    private HttpClient githubApiClient;

    @Post("generate_session")
    public HttpResponse<GenerateSessionIdResponse> generateSessionId(){
        GenerateSessionIdResponse resp = GenerateSessionIdResponse.fromModel(client.generateSessionId());
        return HttpResponse.ok(resp);
    }

    @Post("login")
    public HttpResponse<LoginResponse> login(@Valid @Body LoginRequest body, HttpRequest<?> request) throws URISyntaxException {
        CheckSessionRequest sessionRequest = new CheckSessionRequest();
        sessionRequest.setSessionId(body.getSessionId());
        sessionRequest.setUserId(body.getUserId());
        BackOffice.CheckSessionResponse res = client.checkSession(sessionRequest);
        switch (res.getStatus()){
            case UNRECOGNIZED:
            case NOT_EXISTS:
            case WRONG_USER:
            case EXISTS:
                throw new HttpStatusException(HttpStatus.FORBIDDEN, "Bad session id");
        }
        AuthProviders provider = AuthProviders.fromString(body.getProvider());
        if (provider == null){
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Wrong provider");
        }
        switch (provider){
            case GITHUB:{
                LoginResponse resp = new LoginResponse();
                String redirectURL = UriBuilder.of(new URI("https://github.com/login/oauth/authorize"))
                        .queryParam("client_id", oAuthConfig.getGithub().getClientId())
                        .queryParam("state", body.getSessionId() + "." + body.getUserId() + "." + body.getRedirectUrl())
                        .queryParam("redirect_uri", httpHostResolver.resolve(request) +
                                uriNamingStrategy.resolveUri(AuthController.class) + "/code/" + body.getProvider()
                                )
                        .build().toString();
                resp.setRedirectUrl(redirectURL);
                return HttpResponse.ok(resp);
            }
            default:
                throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Wrong provider");
        }
    }

    @Get("/code/github{?code,state}")
    public HttpResponse<UserCredentials> authCodeGitHub(@QueryValue String code, @QueryValue String state){
        String[] stateValues = state.split("\\.");
        if (stateValues.length < 3){
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Wrong state");
        }

        CheckSessionRequest sessionRequest = new CheckSessionRequest();
        sessionRequest.setSessionId(stateValues[0]);
        sessionRequest.setUserId(stateValues[1]);
        BackOffice.CheckSessionResponse res = client.checkSession(sessionRequest);
        switch (res.getStatus()){
            case UNRECOGNIZED:
            case NOT_EXISTS:
            case WRONG_USER:
            case EXISTS:
                throw new HttpStatusException(HttpStatus.FORBIDDEN, "Bad session id");
        }
        GithubAccessTokenRequest tokenRequest = new GithubAccessTokenRequest();
        tokenRequest.setCode(code);
        tokenRequest.setClient_id(oAuthConfig.getGithub().getClientId());
        tokenRequest.setClient_secret(oAuthConfig.getGithub().getClientSecret());
        HttpResponse<GithubAccessTokenResponse> resp;
        try {
             resp = githubClient.toBlocking().exchange(
                    HttpRequest.POST("/login/oauth/access_token", tokenRequest).accept(MediaType.APPLICATION_JSON_TYPE),
                    GithubAccessTokenResponse.class
            );
        }
        catch (HttpClientException e){
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "Bad code");
        }
        if (resp.getStatus() != HttpStatus.OK){
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "Bad code");
        }
        GithubAccessTokenResponse body = resp.getBody().orElseThrow();
        if (body.getAccess_token() == null){
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "Bad code");
        }
        HttpResponse<GitHubGetUserResponse> result;
        try {
            result = githubApiClient.toBlocking().exchange(
                    HttpRequest.GET("/user")
                            .header("Authorization", "token " + body.getAccess_token())
                            .header("User-Agent", "request")
                            .accept(MediaType.APPLICATION_JSON_TYPE),
                    GitHubGetUserResponse.class
            );
        }
        catch (HttpClientException e){
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "Bad code");
        }
        if (result.getStatus() != HttpStatus.OK){
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "Bad code");
        }
        BackOffice.AuthUserSessionRequest.Builder builder = BackOffice.AuthUserSessionRequest.newBuilder();
        builder
                .setSessionId(stateValues[0])
                .setUserId(stateValues[1])
                .setProvider(AuthProviders.GITHUB.toGrpcMessage())
                .setProviderUserId(result.getBody().orElseThrow().getId());
        BackOffice.AuthUserSessionResponse response = client.authUserSession(builder);

        return HttpResponse.redirect(
                UriBuilder.of(URI.create(stateValues[2]))
                    .queryParam("userId", response.getCredentials().getUserId())
                    .queryParam("sessionId", response.getCredentials().getSessionId())
                    .build()
        );
    }

}
