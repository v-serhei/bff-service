package com.verbitsky.service.auth;

import com.google.common.cache.Cache;

import reactor.core.publisher.Mono;

import org.apache.commons.lang3.StringUtils;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.verbitsky.api.client.ApiResponse;
import com.verbitsky.api.model.dto.SessionDto;
import com.verbitsky.converter.ConverterManager;
import com.verbitsky.exception.AuthException;
import com.verbitsky.model.BffLoginRequest;
import com.verbitsky.model.BffLoginResponse;
import com.verbitsky.model.BffRegisterRequest;
import com.verbitsky.security.CustomOAuth2TokenAuthentication;
import com.verbitsky.security.CustomUserDetails;
import com.verbitsky.security.TokenDataProvider;
import com.verbitsky.service.backend.BackendService;
import com.verbitsky.service.keycloak.KeycloakService;
import com.verbitsky.service.keycloak.response.KeycloakLoginResponse;

import java.io.IOException;
import java.text.ParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.verbitsky.security.CustomOAuth2TokenAuthentication.authenticationFromUserDetails;

@Service
@Slf4j
public class AuthServiceImpl implements AuthService {
    private final KeycloakService keycloakService;
    private final BackendService backendService;
    private final ConverterManager converterManager;
    private final AtomicReference<Cache<String, CustomUserDetails>> userCache;
    private final TokenDataProvider tokenDataProvider;
    private final AuthenticationManager authenticationManager;

    public AuthServiceImpl(@Qualifier("tokenCache") Cache<String, CustomUserDetails> tokenCache,
                           KeycloakService keycloakService,
                           BackendService backendService,
                           ConverterManager converterManager, TokenDataProvider tokenDataProvider,
                           AuthenticationManager authenticationManager) {

        this.keycloakService = keycloakService;
        this.userCache = new AtomicReference<>(tokenCache);
        this.backendService = backendService;
        this.converterManager = converterManager;
        this.tokenDataProvider = tokenDataProvider;
        this.authenticationManager = authenticationManager;
    }

    @Override
    public CustomUserDetails getUserById(String id) {
        return null;
    }

    @Override
    public Mono<BffLoginResponse> processLoginUser(BffLoginRequest loginRequest) {
        return keycloakService
                .processLogin(loginRequest.userName(), loginRequest.password())
                .map(this::processKeycloakLoginResponse)
                .map(userDetails -> new BffLoginResponse(userDetails.getUserId(), userDetails.getSessionId()));
    }

    @Override
    public void processUserLogout(String userId) {
        invalidateSession(userId);
        //todo remove from db
    }

    @Override
    public void processUserRegistration(BffRegisterRequest registerRequest) {
        //todo implement this
    }

    @Override
    public CustomOAuth2TokenAuthentication resolveAuthentication(String userId, String sessionId) {
        return authenticationFromUserDetails(getModelFromStorage(userId, sessionId));
    }

    private boolean isSessionIdValid(CustomUserDetails userDetails, String receivedSessionId) {
        return Objects.nonNull(userDetails)
                && StringUtils.isNotBlank(receivedSessionId)
                && receivedSessionId.equals(userDetails.getSessionId());
    }

    private CustomUserDetails getModelFromStorage(String userId, String sessionId) {
        var userFromCache = getUserFromCache(userId, sessionId);
        return userFromCache
                .orElseGet(() -> getUserSessionFromDb(userId, sessionId));
    }

    private Optional<CustomUserDetails> getUserFromCache(String userId, String sessionId) {
        var userDetails = userCache.get().getIfPresent(userId);

        if (isSessionIdValid(userDetails, sessionId)) {
            if (tokenDataProvider.isTokenValid(userDetails.getJwt())) {
                return Optional.of(userDetails);
            }
            String refreshToken = userDetails.getRefreshToken();
            if (tokenDataProvider.isTokenValid(refreshToken)) {
                return Optional.of(processRefreshToken(refreshToken).block());

            }
        }

        return Optional.empty();
    }

    private CustomUserDetails getUserSessionFromDb(String userId, String sessionId) {
        try {
            return backendService.getUserSession(userId)
                    .map(this::convertUserSessionResponse)
                    .block();
        } catch (Exception exception) {
            log.warn("Requested session id ({}) of user ({}) wasn't found: {}",
                    sessionId, userId, exception.getMessage());
            throw new AuthException("Authentication error", exception, HttpStatus.FORBIDDEN);
        }
    }

    private CustomUserDetails convertUserSessionResponse(ApiResponse response) {
        var converter = converterManager.provideConverter(SessionDto.class, CustomUserDetails.class);
        return converter.convert((SessionDto) response.getResponseObject());
    }

    private Mono<CustomUserDetails> processRefreshToken(String refreshToken) {
        if (tokenDataProvider.isTokenValid(refreshToken)) {
            return keycloakService.processRefreshToken(refreshToken)
                    .map(this::processKeycloakLoginResponse);
        }

        throw new AuthException("Refresh token processing error", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private CustomUserDetails processKeycloakLoginResponse(KeycloakLoginResponse response) {
        try {
            var userDetails = buildUserDetails(response.getAccessToken(), response.getRefreshToken());
            saveUserDetails(userDetails);
            authenticationManager.authenticate(authenticationFromUserDetails(userDetails));
            return userDetails;
        } catch (Exception e) {
            throw new AuthException("KeycloakLoginResponse processing error: " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private CustomUserDetails buildUserDetails(String accessToken, String refreshToken)
            throws ParseException, IOException {

        var tokenParams = tokenDataProvider.getParametersFromToken(accessToken);
        var userAuthorities = tokenDataProvider.getGrantedAuthorities(accessToken);
        var jwt = tokenDataProvider.buildJwt(accessToken, tokenParams);

        return new CustomUserDetails(refreshToken, jwt, userAuthorities);
    }

    private void saveUserDetails(CustomUserDetails userDetails) {
        userCache.get().put(userDetails.getUserId(), userDetails);
        backendService.saveUserSession(buildSessionDto(userDetails)).subscribe()
        ;
    }

    private void invalidateSession(String userId) {
        var customUserDetails = userCache.get().getIfPresent(userId);
        if (Objects.nonNull(customUserDetails)) {
            userCache.get().invalidate(userId);
        }
        //todo remove from db (and keycloak?)
    }

    private SessionDto buildSessionDto(CustomUserDetails userDetails) {
        SessionDto sessionDto = new SessionDto();
        sessionDto.setAccessToken(userDetails.getAccessToken());
        sessionDto.setRefreshToken(userDetails.getRefreshToken());
        sessionDto.setUserId(userDetails.getUserId());

        return sessionDto;
    }
}
