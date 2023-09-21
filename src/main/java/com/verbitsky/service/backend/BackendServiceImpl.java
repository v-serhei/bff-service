package com.verbitsky.service.backend;

import reactor.core.publisher.Mono;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.verbitsky.api.client.ApiResponse;
import com.verbitsky.api.client.RemoteServiceClient;
import com.verbitsky.api.model.SessionModel;
import com.verbitsky.service.backend.request.BackendRequestBuilder;
import com.verbitsky.service.backend.request.RequestType;

import java.util.Map;

import static com.verbitsky.service.backend.request.BackendGetRequest.USER_ID_FIELD;

@Slf4j
@Service
public class BackendServiceImpl implements BackendService {
    private final RemoteServiceClient backendClient;
    private final BackendRequestBuilder requestFactory;

    public BackendServiceImpl(RemoteServiceClient backendClient,
                              BackendRequestBuilder requestFactory) {
        this.backendClient = backendClient;
        this.requestFactory = requestFactory;
    }

    @Override
    public Mono<ApiResponse> getUserSession(String userId) {
        var request = requestFactory.buildRequest(RequestType.USER_SESSION_OP, Map.of(USER_ID_FIELD, userId));

        return backendClient.get(request, SessionModel.class);
    }

    @Override
    public Mono<ApiResponse> saveUserSession(SessionModel sessionDto) {
        var uriParams = Map.of(USER_ID_FIELD, sessionDto.getUserId());
        var bodyFields = sessionDto.fieldsToMap();
        var apiRequest = requestFactory.buildRequest(RequestType.USER_SESSION_OP, uriParams, bodyFields);

        return backendClient.post(apiRequest, SessionModel.class);
    }
}
