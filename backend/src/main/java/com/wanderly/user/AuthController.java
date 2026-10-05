package com.wanderly.user;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** Step 1: create an unverified account and queue a 6-digit code email. 202: the email is sent asynchronously. */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AuthDtos.RegisterResponse register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        return authService.register(request);
    }

    /** Step 2: prove ownership of the email; returns the access token. */
    @PostMapping("/verify")
    public AuthDtos.TokenResponse verify(@Valid @RequestBody AuthDtos.VerifyRequest request) {
        return authService.verify(request);
    }

    /** Always 202, whether or not the email has an account, so it can't be used to probe for users. */
    @PostMapping("/resend-code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendCode(@Valid @RequestBody AuthDtos.ResendRequest request) {
        authService.resendCode(request);
    }

    /** Always 202, whether or not the email has an account. Emails a 6-digit reset code to verified accounts. */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody AuthDtos.ForgotPasswordRequest request) {
        authService.forgotPassword(request);
    }

    /** Code + new password; signs out other sessions and returns a fresh token. */
    @PostMapping("/reset-password")
    public AuthDtos.TokenResponse resetPassword(@Valid @RequestBody AuthDtos.ResetPasswordRequest request) {
        return authService.resetPassword(request);
    }

    @PostMapping("/login")
    public AuthDtos.TokenResponse login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        return authService.login(request);
    }
}
