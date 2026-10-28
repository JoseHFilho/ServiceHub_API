package com.servicehub.security;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties("servicehub.auth-guard")
public class AuthGuardProperties {
    private boolean enabled = true;
    @Min(1) @Max(3600) private int windowSeconds = 60;
    @Min(1) @Max(100000) private int loginIpLimit = 30;
    @Min(1) @Max(100000) private int loginAccountLimit = 10;
    @Min(1) @Max(100000) private int loginGlobalLimit = 120;
    @Min(1) @Max(100000) private int registrationIpLimit = 5;
    @Min(1) @Max(100000) private int registrationAccountLimit = 3;
    @Min(1) @Max(100000) private int registrationGlobalLimit = 30;
    @Min(1) @Max(8) private int maxConcurrent = 2;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public int getWindowSeconds() { return windowSeconds; }
    public void setWindowSeconds(int value) { windowSeconds = value; }
    public int getLoginIpLimit() { return loginIpLimit; }
    public void setLoginIpLimit(int value) { loginIpLimit = value; }
    public int getLoginAccountLimit() { return loginAccountLimit; }
    public void setLoginAccountLimit(int value) { loginAccountLimit = value; }
    public int getLoginGlobalLimit() { return loginGlobalLimit; }
    public void setLoginGlobalLimit(int value) { loginGlobalLimit = value; }
    public int getRegistrationIpLimit() { return registrationIpLimit; }
    public void setRegistrationIpLimit(int value) { registrationIpLimit = value; }
    public int getRegistrationAccountLimit() { return registrationAccountLimit; }
    public void setRegistrationAccountLimit(int value) { registrationAccountLimit = value; }
    public int getRegistrationGlobalLimit() { return registrationGlobalLimit; }
    public void setRegistrationGlobalLimit(int value) { registrationGlobalLimit = value; }
    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int value) { maxConcurrent = value; }
}
