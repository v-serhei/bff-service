package com.verbitsky.model;

import java.io.Serializable;

public record LoginResponseData(String userId, String sessionId, String deviceId) implements Serializable {
}
