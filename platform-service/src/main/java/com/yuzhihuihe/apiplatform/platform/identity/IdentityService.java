package com.yuzhihuihe.apiplatform.platform.identity;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.yuzhihuihe.apiplatform.platform.email.EmailVerificationService;
import com.yuzhihuihe.apiplatform.platform.referral.ReferralApiException;
import com.yuzhihuihe.apiplatform.platform.referral.ReferralService;
import com.yuzhihuihe.apiplatform.platform.sms.PhoneProtectionService;
import com.yuzhihuihe.apiplatform.platform.sms.SmsApiException;
import com.yuzhihuihe.apiplatform.platform.sms.SmsChallengeService;
import com.yuzhihuihe.apiplatform.platform.sms.SmsProofService;
import com.yuzhihuihe.apiplatform.platform.sms.SmsPurpose;
import com.yuzhihuihe.apiplatform.platform.wallet.WalletAccountService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IdentityService {

    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;
    private static final int MAX_AVATAR_BYTES = 2 * 1024 * 1024;
    private static final Set<String> AVATAR_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern STRONG_PASSWORD = Pattern.compile(
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,128}$");

    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final ObjectProvider<SmsChallengeService> smsChallenges;
    private final ObjectProvider<SmsProofService> smsProofs;
    private final ObjectProvider<PhoneProtectionService> phoneProtection;
    private final ObjectProvider<LoginActivityService> loginActivities;
    private final ObjectProvider<PasswordLoginRateLimiter> passwordLoginRateLimiters;
    private final ObjectProvider<LoginHandoffService> loginHandoffs;
    private final ObjectProvider<TerminalSessionService> terminalSessionServices;
    private final ObjectProvider<OpaqueTokenService> opaqueTokenServices;
    private final IdentityRepositories repositories;
    private final IdentityAuthenticationRepository authenticationRepository;
    private final IdentityContactRepository contactRepository;
    private final IdentityProfileRepository profileRepository;
    private final TerminalSessionRepository terminalSessionRepository;
    private final IdentityPasswordRepository passwordRepository;
    private final IdentityRegistrationRepository registrationRepository;
    private final WalletAccountService walletAccounts;
    /**
     * Optional collaborator, defaulting to a consent-inert instance so the constructor-based test
     * fixtures that build this service by hand keep working without a consent store.
     */
    private IdentityConsentService consents = IdentityConsentService.unavailable();
    private EmailVerificationService emailVerifications;
    private ReferralService referrals;
    private final String dummyPasswordHash;

    public IdentityService(
            IdentityRepositories repositories,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService,
            ObjectProvider<SmsChallengeService> smsChallenges,
            ObjectProvider<SmsProofService> smsProofs,
            ObjectProvider<PhoneProtectionService> phoneProtection,
            WalletAccountService walletAccounts) {
        this(repositories, passwordEncoder, jwtTokenService, smsChallenges, smsProofs,
                phoneProtection, walletAccounts, null, null, null, null, null, null);
    }

    public IdentityService(
            IdentityRepositories repositories,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService,
            ObjectProvider<SmsChallengeService> smsChallenges,
            ObjectProvider<SmsProofService> smsProofs,
            ObjectProvider<PhoneProtectionService> phoneProtection,
            WalletAccountService walletAccounts,
            ObjectProvider<LoginActivityService> loginActivities,
            ObjectProvider<PasswordLoginRateLimiter> passwordLoginRateLimiters,
            ObjectProvider<LoginHandoffService> loginHandoffs,
            ObjectProvider<TerminalSessionService> terminalSessionServices,
            ObjectProvider<OpaqueTokenService> opaqueTokenServices,
            IdentityAuthenticationRepository authenticationRepository) {
        this.repositories = repositories;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.smsChallenges = smsChallenges;
        this.smsProofs = smsProofs;
        this.phoneProtection = phoneProtection;
        this.walletAccounts = walletAccounts;
        this.loginActivities = loginActivities;
        this.passwordLoginRateLimiters = passwordLoginRateLimiters;
        this.loginHandoffs = loginHandoffs;
        this.terminalSessionServices = terminalSessionServices;
        this.opaqueTokenServices = opaqueTokenServices;
        this.authenticationRepository = authenticationRepository == null
                ? repositories.authentication() : authenticationRepository;
        this.contactRepository = repositories.contact();
        this.profileRepository = repositories.profile();
        this.terminalSessionRepository = repositories.terminalSession();
        this.passwordRepository = repositories.password();
        this.registrationRepository = repositories.registration();
        this.dummyPasswordHash = passwordEncoder.encode(
                "dummy-password-login-" + UUID.randomUUID());
    }

    @org.springframework.beans.factory.annotation.Autowired
    IdentityService(
            IdentityRepositories repositories,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService,
            ObjectProvider<SmsChallengeService> smsChallenges,
            ObjectProvider<SmsProofService> smsProofs,
            ObjectProvider<PhoneProtectionService> phoneProtection,
            WalletAccountService walletAccounts,
            ObjectProvider<LoginActivityService> loginActivities,
            ObjectProvider<PasswordLoginRateLimiter> passwordLoginRateLimiters,
            ObjectProvider<LoginHandoffService> loginHandoffs,
            ObjectProvider<TerminalSessionService> terminalSessionServices,
            ObjectProvider<OpaqueTokenService> opaqueTokenServices) {
        this(repositories, passwordEncoder, jwtTokenService, smsChallenges, smsProofs,
                phoneProtection, walletAccounts, loginActivities, passwordLoginRateLimiters,
                loginHandoffs, terminalSessionServices, opaqueTokenServices, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    void applyConsentService(ObjectProvider<IdentityConsentService> consentServices) {
        IdentityConsentService available = consentServices.getIfAvailable();
        if (available != null) {
            this.consents = available;
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    void applyReferralService(ObjectProvider<ReferralService> services) {
        this.referrals = services.getIfAvailable();
    }

    @Transactional(noRollbackFor = RegistrationSmsVerificationRejectedException.class)
    public LoginHandoffService.LoginResult register(
            String email,
            String password,
            String displayName,
            String phone,
            UUID challengeId,
            String verificationCode) {
        return register(email, password, displayName, phone, challengeId, verificationCode,
                LoginActivityService.LoginContext.empty());
    }

    @Transactional(noRollbackFor = RegistrationSmsVerificationRejectedException.class)
    public LoginHandoffService.LoginResult register(
            String email,
            String password,
            String displayName,
            String phone,
            UUID challengeId,
            String verificationCode,
            LoginActivityService.LoginContext loginContext) {
        return register(email, password, displayName, phone, challengeId, verificationCode,
                true, null, loginContext);
    }

    @Transactional(noRollbackFor = RegistrationSmsVerificationRejectedException.class)
    public LoginHandoffService.LoginResult register(
            String email,
            String password,
            String displayName,
            String phone,
            UUID challengeId,
            String verificationCode,
            Boolean termsAccepted,
            String consentVersion,
            LoginActivityService.LoginContext loginContext) {
        return register(email, password, displayName, phone, challengeId, verificationCode,
                termsAccepted, consentVersion, null, loginContext);
    }

    @Transactional(noRollbackFor = RegistrationSmsVerificationRejectedException.class)
    public LoginHandoffService.LoginResult register(
            String email,
            String password,
            String displayName,
            String phone,
            UUID challengeId,
            String verificationCode,
            Boolean termsAccepted,
            String consentVersion,
            String invitationCode,
            LoginActivityService.LoginContext loginContext) {
        if (phone == null || phone.isBlank() || challengeId == null
                || verificationCode == null || verificationCode.isBlank()) {
            throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY, "sms_verification_required");
        }
        SmsChallengeService challenges = requiredSmsChallenges();
        PhoneProtectionService phones = requiredPhoneProtection();
        String normalizedEmail = normalizeEmail(email);
        validatePassword(password);
        String normalizedName = requireDisplayName(displayName);
        if (registrationRepository.emailExists(normalizedEmail)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email is already registered");
        }
        ReferralService.AttributionTarget referral = prepareReferral(invitationCode);

        SmsChallengeService.RegistrationChallenge registration;
        PhoneProtectionService.ProtectedPhone requestedPhone;
        try {
            challenges.verifyRegistration(challengeId, verificationCode);
        } catch (RuntimeException error) {
            throw registrationVerificationError(error);
        }
        try {
            registration = challenges.consumeVerifiedRegistration(challengeId);
            requestedPhone = phones.protect(phone);
        } catch (RuntimeException error) {
            throw smsError(error, "invalid_sms_challenge");
        }
        if (!MessageDigest.isEqual(
                registration.phoneLookupHash().getBytes(StandardCharsets.US_ASCII),
                requestedPhone.lookupHash().getBytes(StandardCharsets.US_ASCII))) {
            throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_sms_challenge");
        }
        if (registrationRepository.phoneExists(registration.phoneLookupHash())) {
            throw new SmsApiException(HttpStatus.CONFLICT, "phone_unavailable");
        }
        // The registration form collects consent before the challenge is issued, so a request
        // without it must not create an account.
        String acceptedVersion = consents.requireAcceptance(termsAccepted, consentVersion);

        UUID userId = UUID.randomUUID();
        UUID tenantId = userId;
        try {
            registrationRepository.createPasswordAccount(
                    userId,
                    tenantId,
                    normalizedEmail,
                    passwordEncoder.encode(password),
                    normalizedName,
                    registration.phoneCiphertext(),
                    registration.phoneLookupHash(),
                    registration.phoneLast4());
        } catch (DataIntegrityViolationException exception) {
            throw identityConflict(exception);
        }
        recordReferral(referral, userId);
        provisionUser(tenantId, userId);
        consents.recordAcceptance(
                tenantId, userId, acceptedVersion, IdentityConsentService.SOURCE_REGISTRATION);
        UserProfile user = new UserProfile(userId, tenantId, normalizedEmail, normalizedName, "USER");
        return requiredLoginHandoffs().begin(user, loginContext);
    }

    @Transactional
    public LoginHandoffService.LoginResult login(String identifier, String password) {
        return login(identifier, password, LoginActivityService.LoginContext.empty());
    }

    @Transactional
    public LoginHandoffService.LoginResult login(
            String identifier, String password, LoginActivityService.LoginContext loginContext) {
        String normalizedIdentifier = identifier == null ? "" : identifier.trim();
        String limiterIdentifier = "<missing>";
        IdentityAuthenticationRepository.LoginIdentifierType identifierType = null;
        String lookupValue = null;
        if (!normalizedIdentifier.isBlank()
                && EMAIL_PATTERN.matcher(normalizedIdentifier).matches()) {
            identifierType = IdentityAuthenticationRepository.LoginIdentifierType.EMAIL;
            lookupValue = normalizedIdentifier.toLowerCase(Locale.ROOT);
            limiterIdentifier = lookupValue;
        } else if (!normalizedIdentifier.isBlank()) {
            identifierType = IdentityAuthenticationRepository.LoginIdentifierType.PHONE;
            try {
                PhoneProtectionService phones = requiredPhoneProtection();
                limiterIdentifier = phones.normalize(normalizedIdentifier);
                lookupValue = phones.protect(limiterIdentifier).lookupHash();
            } catch (RuntimeException ignored) {
                identifierType = null;
                lookupValue = null;
                limiterIdentifier = normalizedIdentifier.toLowerCase(Locale.ROOT);
            }
        }
        PasswordLoginRateLimiter limiter = requiredPasswordLoginRateLimiter();
        String clientIp = loginContext == null || loginContext.ipAddress() == null
                || loginContext.ipAddress().isBlank() ? "<unknown>" : loginContext.ipAddress();
        PasswordLoginRateLimiter.LoginAttempt attempt = limiter.inspect(
                limiterIdentifier, clientIp);
        IdentityAuthenticationRepository.AuthenticationUser account = identifierType == null
                ? null
                : authenticationRepository.findPasswordUser(identifierType, lookupValue).orElse(null);
        UserProfile authenticated = account == null ? null : userProfile(account);
        String submittedPassword = password == null ? "" : password;
        // An unauthenticated caller controls this string, so it is bounded before the encoder sees
        // it. This is input bounding and policy consistency, not a CPU fix: BCryptPasswordEncoder
        // .matches() costs the same ~450 ms at cost 12 for any candidate length (measured 70 chars
        // -> 445-464 ms, 1 MiB -> 413-421 ms, 8 MiB -> 432-459 ms, 19 MiB -> 461-474 ms), because
        // checkpw routes through hashpwforcheck, which truncates at 72 bytes instead of hashing the
        // whole input. The guard still keeps an attacker-sized string out of the encoder and matches
        // the 72-byte policy the registration path already enforces. It answers with the same
        // invalid-credentials error as any other mismatch so the response cannot be used to probe
        // the password policy.
        if (submittedPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_PASSWORD_BYTES) {
            limiter.recordFailure(attempt);
            throw IdentityApiException.invalidCredentials();
        }
        String passwordHash = account == null || account.passwordHash() == null
                ? dummyPasswordHash : account.passwordHash();
        boolean passwordMatches = passwordEncoder.matches(submittedPassword, passwordHash);
        if (authenticated == null || password == null || !passwordMatches) {
            limiter.recordFailure(attempt);
            throw IdentityApiException.invalidCredentials();
        }
        limiter.recordSuccess(attempt);
        authenticationRepository.updateLastLogin(authenticated.id());
        LoginHandoffService.LoginResult result = requiredLoginHandoffs().begin(authenticated, loginContext);
        recordCompletedLogin(authenticated, loginContext, result);
        return result;
    }

    private PasswordLoginRateLimiter requiredPasswordLoginRateLimiter() {
        PasswordLoginRateLimiter limiter = passwordLoginRateLimiters == null
                ? null : passwordLoginRateLimiters.getIfAvailable();
        if (limiter == null) {
            throw IdentityApiException.rateLimitUnavailable();
        }
        return limiter;
    }

    @Transactional(noRollbackFor = RegistrationSmsVerificationRejectedException.class)
    public IdentityLoginResult authenticateWithPhoneChallenge(
            String identifier, UUID challengeId, String verificationCode) {
        return authenticateWithPhoneChallenge(
                identifier, challengeId, verificationCode, LoginActivityService.LoginContext.empty());
    }

    @Transactional(noRollbackFor = RegistrationSmsVerificationRejectedException.class)
    public IdentityLoginResult authenticateWithPhoneChallenge(
            String identifier,
            UUID challengeId,
            String verificationCode,
            LoginActivityService.LoginContext loginContext) {
        return authenticateWithPhoneChallenge(
                identifier, challengeId, verificationCode, null, null, loginContext);
    }

    @Transactional(noRollbackFor = RegistrationSmsVerificationRejectedException.class)
    public IdentityLoginResult authenticateWithPhoneChallenge(
            String identifier,
            UUID challengeId,
            String verificationCode,
            Boolean termsAccepted,
            String consentVersion,
            LoginActivityService.LoginContext loginContext) {
        if (challengeId == null || verificationCode == null || verificationCode.isBlank()) {
            throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_sms_challenge");
        }
        SmsChallengeService challenges = requiredSmsChallenges();
        PhoneProtectionService phones = requiredPhoneProtection();
        SmsChallengeService.VerifiedChallenge verified;
        PhoneProtectionService.ProtectedPhone requestedPhone;
        try {
            requestedPhone = phones.protect(identifier);
        } catch (RuntimeException error) {
            throw smsError(error, "invalid_sms_challenge");
        }
        try {
            verified = challenges.verifyRegistration(challengeId, verificationCode);
        } catch (RuntimeException error) {
            throw registrationVerificationError(error);
        }
        SmsChallengeService.RegistrationChallenge registration;
        try {
            registration = challenges.inspectVerifiedRegistration(challengeId);
        } catch (RuntimeException error) {
            throw smsError(error, "invalid_sms_challenge");
        }
        if (!MessageDigest.isEqual(
                registration.phoneLookupHash().getBytes(StandardCharsets.US_ASCII),
                requestedPhone.lookupHash().getBytes(StandardCharsets.US_ASCII))) {
            throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_sms_challenge");
        }

        UserProfile existing = findUserByPhone(registration.phoneLookupHash());
        if (existing != null) {
            try {
                challenges.consumeVerifiedRegistration(challengeId);
            } catch (RuntimeException error) {
                throw smsError(error, "invalid_sms_challenge");
            }
            authenticationRepository.updateLastLogin(existing.id());
            LoginHandoffService.LoginResult result = requiredLoginHandoffs().begin(existing, loginContext);
            recordCompletedLogin(existing, loginContext, result);
            return new IdentityLoginResult(result, false);
        }

        // A verified new number remains a short-lived pending registration. No user, session,
        // consent ledger entry, or desktop authorization exists until the password is set.
        consents.requireAcceptance(termsAccepted, consentVersion);
        return IdentityLoginResult.pending(challengeId, verified.registrationExpiresAt());
    }

    @org.springframework.beans.factory.annotation.Autowired
    void applyEmailVerificationService(ObjectProvider<EmailVerificationService> services) {
        this.emailVerifications = services.getIfAvailable();
    }

    @Transactional
    public LoginHandoffService.LoginResult completePhoneRegistration(
            UUID challengeId,
            String newPassword,
            Boolean termsAccepted,
            String consentVersion,
            LoginActivityService.LoginContext loginContext) {
        return completePhoneRegistration(challengeId, newPassword, termsAccepted,
                consentVersion, null, loginContext);
    }

    @Transactional
    public LoginHandoffService.LoginResult completePhoneRegistration(
            UUID challengeId,
            String newPassword,
            Boolean termsAccepted,
            String consentVersion,
            String invitationCode,
            LoginActivityService.LoginContext loginContext) {
        validatePassword(newPassword);
        String acceptedVersion = consents.requireAcceptance(termsAccepted, consentVersion);
        ReferralService.AttributionTarget referral = prepareReferral(invitationCode);
        SmsChallengeService.RegistrationChallenge registration;
        try {
            registration = requiredSmsChallenges().consumeVerifiedRegistration(challengeId);
        } catch (RuntimeException error) {
            throw smsError(error, "invalid_registration_challenge");
        }
        if (registrationRepository.phoneExists(registration.phoneLookupHash())) {
            throw new SmsApiException(HttpStatus.CONFLICT, "phone_unavailable");
        }
        String maskedPhone = requiredPhoneProtection().mask(registration.phoneCiphertext());
        UUID userId = UUID.randomUUID();
        UUID tenantId = userId;
        String defaultDisplayName = maskedPhone.substring(0, 3) + "用户";
        try {
            registrationRepository.createPasswordAccount(
                    userId,
                    tenantId,
                    null,
                    passwordEncoder.encode(newPassword),
                    defaultDisplayName,
                    registration.phoneCiphertext(),
                    registration.phoneLookupHash(),
                    registration.phoneLast4());
        } catch (DataIntegrityViolationException exception) {
            throw identityConflict(exception);
        }
        recordReferral(referral, userId);
        provisionUser(tenantId, userId);
        consents.recordAcceptance(
                tenantId, userId, acceptedVersion, IdentityConsentService.SOURCE_PHONE_IDENTITY);
        UserProfile created = new UserProfile(
                userId, tenantId, null, defaultDisplayName, "USER", true);
        LoginHandoffService.LoginResult result = requiredLoginHandoffs().begin(created, loginContext);
        recordCompletedLogin(created, loginContext, result);
        return result;
    }

    @Transactional
    public IdentityLoginResult authenticateWithVerifiedEmail(
            EmailVerificationService.VerifiedRegistration verified,
            Boolean termsAccepted,
            String consentVersion,
            LoginActivityService.LoginContext loginContext) {
        String normalizedEmail = normalizeEmail(verified.email());
        UserProfile existing = authenticationRepository.lockEmailUser(normalizedEmail)
                .map(this::userProfile)
                .orElse(null);
        if (existing != null) {
            requiredEmailVerifications().consumeVerifiedRegistration(verified.challengeId());
            authenticationRepository.updateLastLogin(existing.id());
            LoginHandoffService.LoginResult result = requiredLoginHandoffs().begin(existing, loginContext);
            recordCompletedLogin(existing, loginContext, result);
            return new IdentityLoginResult(result, false);
        }
        // A new email stays pending until password setup, just like a new phone number.
        consents.requireAcceptance(termsAccepted, consentVersion);
        return IdentityLoginResult.pending(verified.challengeId(), verified.expiresAt());
    }

    @Transactional
    public LoginHandoffService.LoginResult completeEmailRegistration(
            UUID challengeId,
            String newPassword,
            Boolean termsAccepted,
            String consentVersion,
            LoginActivityService.LoginContext loginContext) {
        return completeEmailRegistration(challengeId, newPassword, termsAccepted,
                consentVersion, null, loginContext);
    }

    @Transactional
    public LoginHandoffService.LoginResult completeEmailRegistration(
            UUID challengeId,
            String newPassword,
            Boolean termsAccepted,
            String consentVersion,
            String invitationCode,
            LoginActivityService.LoginContext loginContext) {
        validatePassword(newPassword);
        String acceptedVersion = consents.requireAcceptance(termsAccepted, consentVersion);
        ReferralService.AttributionTarget referral = prepareReferral(invitationCode);
        String normalizedEmail = requiredEmailVerifications()
                .consumeVerifiedRegistration(challengeId);
        if (registrationRepository.emailExists(normalizedEmail)) {
            throw new SmsApiException(HttpStatus.CONFLICT, "email_unavailable");
        }
        UUID userId = UUID.randomUUID();
        UUID tenantId = userId;
        try {
            registrationRepository.createEmailPasswordAccount(
                    userId, tenantId, normalizedEmail, passwordEncoder.encode(newPassword));
        } catch (DataIntegrityViolationException exception) {
            if (repositories.violatesUniqueConstraint(exception, "uq_app_user_email")) {
                throw new SmsApiException(HttpStatus.CONFLICT, "email_unavailable");
            }
            throw identityConflict(exception);
        }
        recordReferral(referral, userId);
        provisionUser(tenantId, userId);
        consents.recordAcceptance(
                tenantId, userId, acceptedVersion, IdentityConsentService.SOURCE_EMAIL_IDENTITY);
        UserProfile created = new UserProfile(
                userId, tenantId, normalizedEmail, "邮箱用户", "USER", true);
        LoginHandoffService.LoginResult result = requiredLoginHandoffs().begin(created, loginContext);
        recordCompletedLogin(created, loginContext, result);
        return result;
    }

    private ReferralService.AttributionTarget prepareReferral(String invitationCode) {
        if (referrals != null) {
            return referrals.resolveOptional(invitationCode);
        }
        if (invitationCode == null || invitationCode.isBlank()) {
            return null;
        }
        throw new IllegalStateException("referral_service_missing");
    }

    private void recordReferral(ReferralService.AttributionTarget referral, UUID inviteeUserId) {
        if (referral != null) {
            referrals.record(referral, inviteeUserId);
        }
    }

    private EmailVerificationService requiredEmailVerifications() {
        if (emailVerifications == null) {
            throw new SmsApiException(HttpStatus.SERVICE_UNAVAILABLE, "email_disabled");
        }
        return emailVerifications;
    }

    @Transactional
    public void bindEmail(UUID tenantId, UUID userId, String email) {
        String normalizedEmail = normalizeEmail(email);
        contactRepository.lockActiveUser(tenantId, userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "profile not found"));
        if (contactRepository.emailUsedByAnotherUser(normalizedEmail, userId)) {
            throw new SmsApiException(HttpStatus.CONFLICT, "email_already_bound");
        }
        try {
            contactRepository.bindEmail(tenantId, userId, normalizedEmail);
        } catch (DataIntegrityViolationException exception) {
            throw identityConflict(exception);
        }
    }

    @Transactional(noRollbackFor = SmsLoginRejectedException.class)
    public LoginHandoffService.LoginResult loginWithSms(UUID challengeId, String code) {
        return loginWithSms(challengeId, code, LoginActivityService.LoginContext.empty());
    }

    @Transactional(noRollbackFor = SmsLoginRejectedException.class)
    public LoginHandoffService.LoginResult loginWithSms(
            UUID challengeId, String code, LoginActivityService.LoginContext loginContext) {
        SmsChallengeService.VerifiedChallenge verified;
        try {
            verified = requiredSmsChallenges().verifyLogin(challengeId, code);
        } catch (IllegalArgumentException rejected) {
            throw new SmsLoginRejectedException();
        }
        UserProfile user = authenticationRepository.lockSmsLoginUser(
                challengeId, verified.userId(), verified.tenantId())
                .map(this::userProfile)
                .orElseThrow(SmsLoginRejectedException::new);
        authenticationRepository.updateLastLogin(user.id());
        LoginHandoffService.LoginResult result = requiredLoginHandoffs().begin(user, loginContext);
        recordCompletedLogin(user, loginContext, result);
        return result;
    }

    @Transactional
    public AccessSession refresh(
            String rawRefreshToken, String rawCsrfToken, String rawDeviceId) {
        return requiredTerminalSessions()
                .refresh(rawRefreshToken, rawCsrfToken, rawDeviceId).session();
    }

    @Transactional
    public void logout(String rawRefreshToken, String rawCsrfToken) {
        requiredTerminalSessions().logout(rawRefreshToken, rawCsrfToken);
    }

    public ProfileView profile(UUID tenantId, UUID userId) {
        if (tenantId == null || userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication is required");
        }
        IdentityProfileRepository.ProfileAccount account = profileRepository
                .findProfile(tenantId, userId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "profile not found"));
        List<SessionView> sessions = terminalSessionRepository.recentSessions(tenantId, userId).stream()
                .map(session -> new SessionView(
                        session.id(), session.status(), session.createdAt(), session.expiresAt(),
                        session.revokedAt()))
                .toList();
        int activeDeviceCount = terminalSessionRepository.countActiveDesktopDevices(tenantId, userId);
        boolean phoneVerified = account.phoneVerifiedAt() != null;
        String maskedPhone = phoneVerified && account.phoneLast4() != null
                ? "***-****-" + account.phoneLast4() : null;
        if (phoneVerified && account.phoneCiphertext() != null) {
            PhoneProtectionService phones = phoneProtection.getIfAvailable();
            if (phones != null) {
                try {
                    maskedPhone = phones.mask(account.phoneCiphertext());
                } catch (RuntimeException ignored) {
                    // Keep the non-sensitive last-four fallback when ciphertext cannot be decrypted.
                }
            }
        }
        return new ProfileView(
                account.id(), account.tenantId(), account.userNo(), account.email(), account.displayName(),
                account.status(), account.lastLoginAt(), account.createdAt(), maskedPhone,
                phoneVerified, phoneVerified ? "VERIFIED" : "UNBOUND",
                account.smsNotificationsEnabled(), account.passwordSet(),
                account.avatarUpdatedAt() == null ? null : "/console/platform/profile/avatar",
                account.avatarUpdatedAt(), account.maxLoginDevices(), activeDeviceCount, sessions);
    }

    @Transactional
    public void updateAvatar(UUID tenantId, UUID userId, byte[] bytes, String contentType) {
        if (tenantId == null || userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication is required");
        }
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_AVATAR_BYTES) {
            throw IdentityApiException.avatarFileTooLarge();
        }
        String normalizedType = contentType == null ? "" : contentType.strip().toLowerCase(Locale.ROOT);
        if (!AVATAR_CONTENT_TYPES.contains(normalizedType)) {
            throw IdentityApiException.avatarFileTypeNotSupported();
        }
        String detectedType = detectAvatarContentType(bytes);
        if (!normalizedType.equals(detectedType)) {
            throw IdentityApiException.avatarFileFormatMismatch();
        }
        int updated = profileRepository.updateAvatar(
                tenantId, userId, bytes, normalizedType);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "profile not found");
        }
    }

    public String boundEmail(UUID tenantId, UUID userId) {
        return profileRepository.findBoundEmail(tenantId, userId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "profile not found"))
                .email();
    }

    public AvatarContent avatar(UUID tenantId, UUID userId) {
        if (tenantId == null || userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication is required");
        }
        return profileRepository.findAvatar(tenantId, userId)
                .map(content -> new AvatarContent(content.bytes(), content.contentType()))
                .orElse(null);
    }

    private String detectAvatarContentType(byte[] bytes) {
        try (var byteInput = new ByteArrayInputStream(bytes);
             ImageInputStream imageInput = ImageIO.createImageInputStream(byteInput)) {
            if (imageInput == null) {
                throw IdentityApiException.avatarFileInvalid();
            }
            var readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw IdentityApiException.avatarFileInvalid();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw IdentityApiException.avatarFileInvalid();
                }
                if (width > 4096 || height > 4096) {
                    throw IdentityApiException.avatarFileDimensionsExceeded();
                }
                if (reader.read(0) == null) {
                    throw IdentityApiException.avatarFileInvalid();
                }
                return switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                    case "jpeg", "jpg" -> "image/jpeg";
                    case "png" -> "image/png";
                    case "gif" -> "image/gif";
                    default -> throw IdentityApiException.avatarFileTypeNotSupported();
                };
            } finally {
                reader.dispose();
            }
        } catch (IOException error) {
            throw IdentityApiException.avatarFileInvalid();
        }
    }

    public SmsChallengeService.SmsChallenge issuePhoneChallenge(
            UUID tenantId,
            UUID userId,
            SmsPurpose purpose,
            String phone,
            String requestIpHash) {
        if (purpose != SmsPurpose.PHONE_BIND && purpose != SmsPurpose.PHONE_CHANGE) {
            throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_sms_purpose");
        }
        try {
            return requiredSmsChallenges().issue(purpose, phone, tenantId, userId, requestIpHash);
        } catch (RuntimeException error) {
            throw smsError(error, "sms_request_failed");
        }
    }

    @Transactional
    public void bindPhone(UUID tenantId, UUID userId, UUID challengeId, SmsPurpose purpose) {
        bindPhone(tenantId, userId, challengeId, purpose, null);
    }

    @Transactional
    public void bindPhone(
            UUID tenantId,
            UUID userId,
            UUID challengeId,
            SmsPurpose purpose,
            String currentPhoneProof) {
        if (purpose != SmsPurpose.PHONE_BIND && purpose != SmsPurpose.PHONE_CHANGE) {
            throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_phone_challenge");
        }
        boolean currentlyBound = contactRepository.lockPhoneBindingState(tenantId, userId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "profile not found"));
        if ((purpose == SmsPurpose.PHONE_BIND && currentlyBound)
                || (purpose == SmsPurpose.PHONE_CHANGE && !currentlyBound)) {
            throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_phone_challenge");
        }
        if (currentlyBound) {
            SmsProofService proofs = smsProofs.getIfAvailable();
            if (proofs == null || currentPhoneProof == null || currentPhoneProof.isBlank()) {
                throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "current_phone_verification_required");
            }
            try {
                proofs.consume(currentPhoneProof, tenantId, userId, SmsPurpose.PHONE_CHANGE_CONFIRM);
            } catch (RuntimeException error) {
                throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid_current_phone_proof");
            }
        }
        SmsChallengeService.PhoneChallenge phoneChallenge;
        try {
            phoneChallenge = requiredSmsChallenges().consumeVerifiedPhoneChallenge(
                    challengeId, tenantId, userId, purpose);
        } catch (RuntimeException error) {
            throw smsError(error, "invalid_phone_challenge");
        }
        String masked = requiredPhoneProtection().mask(phoneChallenge.phoneCiphertext());
        try {
            contactRepository.bindPhone(
                    tenantId,
                    userId,
                    phoneChallenge.phoneCiphertext(),
                    phoneChallenge.phoneLookupHash(),
                    phoneChallenge.phoneLast4());
        } catch (DataIntegrityViolationException conflict) {
            throw identityConflict(conflict);
        }
        contactRepository.auditPhoneBinding(
                tenantId,
                userId,
                purpose == SmsPurpose.PHONE_BIND ? "PHONE_BOUND" : "PHONE_CHANGED",
                masked);
    }

    @Transactional
    public void updateSmsPreference(UUID tenantId, UUID userId, boolean enabled) {
        int updated = profileRepository.updateSmsPreference(tenantId, userId, enabled);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "profile not found");
        }
        profileRepository.auditSmsPreference(tenantId, userId, enabled);
    }

    @Transactional
    public String updateDisplayName(UUID tenantId, UUID userId, String displayName) {
        String normalizedName = requireDisplayName(displayName);
        int updated = profileRepository.updateDisplayName(tenantId, userId, normalizedName);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "profile not found");
        }
        profileRepository.auditDisplayName(tenantId, userId);
        return normalizedName;
    }

    @Transactional
    public int updateLoginDeviceLimit(UUID tenantId, UUID userId, Integer maxLoginDevices) {
        if (maxLoginDevices == null || maxLoginDevices < 1 || maxLoginDevices > 10) {
            throw IdentityApiException.invalidLoginDeviceLimit();
        }
        int updated = profileRepository.updateLoginDeviceLimit(
                tenantId, userId, maxLoginDevices);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "profile not found");
        }
        profileRepository.auditLoginDeviceLimit(tenantId, userId, maxLoginDevices);
        return maxLoginDevices;
    }

    @Transactional
    public AccessSession changePassword(
            UUID tenantId,
            UUID userId,
            String currentPassword,
            String newPassword,
            String smsProof,
            String invitationCode,
            LoginActivityService.LoginContext loginContext) {
        if (tenantId == null || userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication is required");
        }
        validatePassword(newPassword);
        IdentityPasswordRepository.PasswordAccount account = passwordRepository
                .lockActiveAccount(tenantId, userId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "profile not found"));
        // Legacy accounts already exist with no password; attribute only at this one-time setup.
        boolean firstPasswordSetup = account.passwordHash() == null;
        if (invitationCode != null && !invitationCode.isBlank()
                && (!firstPasswordSetup || (referrals != null && referrals.hasAttribution(userId)))) {
            throw ReferralApiException.invitationNotAllowed();
        }
        ReferralService.AttributionTarget referral = firstPasswordSetup
                ? prepareReferral(invitationCode) : null;
        if (account.passwordHash() != null) {
            if (currentPassword == null || !passwordEncoder.matches(currentPassword, account.passwordHash())) {
                throw unprocessable("current password is invalid");
            }
            if (passwordEncoder.matches(newPassword, account.passwordHash())) {
                throw unprocessable("new password must be different");
            }
        }
        if (account.passwordHash() != null && account.phoneVerified()) {
            if (smsProof == null || smsProof.isBlank()) {
                throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY, "sms_verification_required");
            }
            SmsProofService proofs = smsProofs.getIfAvailable();
            if (proofs == null) {
                throw new SmsApiException(HttpStatus.SERVICE_UNAVAILABLE, "sms_disabled");
            }
            try {
                proofs.consume(smsProof, tenantId, userId, SmsPurpose.CHANGE_PASSWORD);
            } catch (RuntimeException error) {
                throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_sms_proof");
            }
        }
        passwordRepository.updatePassword(
                tenantId, userId, passwordEncoder.encode(newPassword));
        recordReferral(referral, userId);
        requiredTerminalSessions().revokeAll(tenantId, userId, "PASSWORD_CHANGED");
        passwordRepository.auditPasswordChanged(tenantId, userId, account.phoneVerified());
        if (!firstPasswordSetup) {
            return null;
        }
        LoginHandoffService.LoginResult loginResult = requiredLoginHandoffs().begin(
                findActiveUser(tenantId, userId), loginContext);
        if (loginResult instanceof LoginHandoffService.Completed completed) {
            return completed.session();
        }
        throw IdentityApiException.loginHandoffUnavailable();
    }

    public void validateResetPassword(String newPassword) {
        validatePassword(newPassword);
    }

    @Transactional
    public void resetPasswordWithPhone(UUID challengeId, String code, String newPassword) {
        validatePassword(newPassword);
        SmsChallengeService.VerifiedChallenge verified;
        try {
            verified = requiredSmsChallenges().verifyPasswordReset(challengeId, code);
        } catch (RuntimeException error) {
            throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "invalid_password_reset_challenge");
        }
        resetPassword(verified.tenantId(), verified.userId(), newPassword, "PHONE");
    }

    @Transactional
    public void resetPasswordWithEmail(String email, String newPassword) {
        validatePassword(newPassword);
        String normalizedEmail = normalizeEmail(email);
        List<IdentityPasswordRepository.PasswordResetAccount> accounts =
                passwordRepository.lockActiveAccountsByEmail(normalizedEmail);
        if (accounts.size() != 1) {
            throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "invalid_password_reset_challenge");
        }
        IdentityPasswordRepository.PasswordResetAccount account = accounts.getFirst();
        resetPassword(account.tenantId(), account.userId(), newPassword, "EMAIL");
    }

    private void resetPassword(
            UUID tenantId, UUID userId, String newPassword, String verificationChannel) {
        int updated = passwordRepository.updateActivePassword(
                tenantId, userId, passwordEncoder.encode(newPassword));
        if (updated != 1) {
            throw new SmsApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "invalid_password_reset_challenge");
        }
        requiredTerminalSessions().revokeAll(tenantId, userId, "PASSWORD_RESET");
        passwordRepository.auditPasswordReset(
                tenantId, userId, verificationChannel);
    }

    String newCsrfToken() {
        return requiredOpaqueTokens().generate();
    }

    private UserProfile findUserByPhone(String phoneLookupHash) {
        return authenticationRepository.lockPhoneUser(phoneLookupHash)
                .map(this::userProfile)
                .orElse(null);
    }

    private UserProfile findActiveUser(UUID tenantId, UUID userId) {
        return authenticationRepository.findActiveUser(tenantId, userId)
                .map(this::userProfile)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "profile not found"));
    }

    private UserProfile userProfile(
            IdentityAuthenticationRepository.AuthenticationUser account) {
        return new UserProfile(
                account.id(),
                account.tenantId(),
                account.email(),
                account.displayName(),
                account.role(),
                account.passwordHash() != null,
                account.status(),
                account.authVersion());
    }

    private void provisionUser(UUID tenantId, UUID userId) {
        walletAccounts.provision(tenantId, userId);
        registrationRepository.assignUserRole(tenantId, userId);
    }


    private String normalizeEmail(String email) {
        String normalized = requireValue(email, "email is required").toLowerCase(Locale.ROOT);
        if (normalized.length() > 320 || !EMAIL_PATTERN.matcher(normalized).matches()) {
            throw unprocessable("email is invalid");
        }
        return normalized;
    }

    private void validatePassword(String password) {
        if (password == null
                || password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_PASSWORD_BYTES
                || !STRONG_PASSWORD.matcher(password).matches()) {
            throw unprocessable("password does not meet complexity requirements");
        }
    }

    private String requireDisplayName(String displayName) {
        String value = requireValue(displayName, "display name is required");
        if (value.length() > 100) {
            throw unprocessable("display name is too long");
        }
        return value;
    }

    private String requireValue(String value, String message) {
        if (value == null || value.isBlank()) {
            throw unprocessable(message);
        }
        return value.trim();
    }

    private ResponseStatusException unprocessable(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
    }

    private void recordLoginSuccess(UserProfile user, LoginActivityService.LoginContext loginContext) {
        if (loginActivities == null) return;
        LoginActivityService activityService = loginActivities.getIfAvailable();
        if (activityService != null) activityService.recordSuccess(user, loginContext);
    }

    private void recordCompletedLogin(
            UserProfile user,
            LoginActivityService.LoginContext loginContext,
            LoginHandoffService.LoginResult result) {
        if (result instanceof LoginHandoffService.Completed) {
            recordLoginSuccess(user, loginContext);
        }
    }

    public AccessSession completeHandoff(
            UUID requestId,
            String pendingToken,
            String rawDeviceId,
            LoginActivityService.LoginContext loginContext) {
        AccessSession session = requiredLoginHandoffs().complete(
                requestId, pendingToken, rawDeviceId, loginContext);
        recordLoginSuccess(session.user(), loginContext);
        return session;
    }

    public AccountState heartbeat(
            LoginHandoffService.AuthenticatedTerminal terminal, String rawDeviceId) {
        requiredTerminalSessions().heartbeat(
                terminal.user().tenantId(), terminal.user().id(), terminal.sessionId(),
                terminal.clientType(), rawDeviceId);
        return accountState(terminal.user().tenantId(), terminal.user().id());
    }

    public void requestBrowserClose(
            LoginHandoffService.AuthenticatedTerminal terminal, String rawDeviceId) {
        requiredTerminalSessions().requestBrowserClose(
                terminal.user().tenantId(), terminal.user().id(), terminal.sessionId(),
                terminal.clientType(), rawDeviceId);
    }

    public AccountState accountState(UUID tenantId, UUID userId) {
        if (tenantId == null || userId == null) throw IdentityApiException.invalidCredentials();
        IdentityProfileRepository.ProfileAccountState state = profileRepository
                .findAccountState(tenantId, userId)
                .orElseThrow(IdentityApiException::invalidCredentials);
        return new AccountState(state.status(), state.disabledReason(), state.disabledAt());
    }

    public void validateTerminalDevice(
            LoginHandoffService.AuthenticatedTerminal terminal, String rawDeviceId) {
        requiredTerminalSessions().validateDevice(
                terminal.user().tenantId(), terminal.user().id(), terminal.sessionId(),
                terminal.clientType(), rawDeviceId);
    }

    private LoginHandoffService requiredLoginHandoffs() {
        LoginHandoffService service = loginHandoffs == null ? null : loginHandoffs.getIfAvailable();
        if (service == null) throw IdentityApiException.loginHandoffUnavailable();
        return service;
    }

    private TerminalSessionService requiredTerminalSessions() {
        TerminalSessionService service = terminalSessionServices == null
                ? null : terminalSessionServices.getIfAvailable();
        if (service == null) throw IdentityApiException.loginHandoffUnavailable();
        return service;
    }

    private OpaqueTokenService requiredOpaqueTokens() {
        OpaqueTokenService service = opaqueTokenServices == null ? null : opaqueTokenServices.getIfAvailable();
        if (service == null) throw IdentityApiException.loginHandoffUnavailable();
        return service;
    }

    private static final class SmsLoginRejectedException extends ResponseStatusException {
        private SmsLoginRejectedException() {
            super(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }
    }

    private static final class RegistrationSmsVerificationRejectedException extends SmsApiException {
        private RegistrationSmsVerificationRejectedException(SmsApiException error) {
            super(error.status(), error.code());
        }
    }

    private SmsChallengeService requiredSmsChallenges() {
        SmsChallengeService service = smsChallenges.getIfAvailable();
        if (service == null) {
            throw new SmsApiException(HttpStatus.SERVICE_UNAVAILABLE, "sms_disabled");
        }
        return service;
    }

    private PhoneProtectionService requiredPhoneProtection() {
        PhoneProtectionService service = phoneProtection.getIfAvailable();
        if (service == null) {
            throw new SmsApiException(HttpStatus.SERVICE_UNAVAILABLE, "sms_disabled");
        }
        return service;
    }

    private SmsApiException smsError(RuntimeException error, String fallback) {
        if (error instanceof SmsApiException smsApiException) {
            return smsApiException;
        }
        return SmsApiException.from(error, fallback);
    }

    private SmsApiException registrationVerificationError(RuntimeException error) {
        SmsApiException mapped = smsError(error, "invalid_sms_challenge");
        if (error instanceof IllegalArgumentException
                && ("invalid_sms_code".equals(mapped.code())
                || "challenge_attempts_exhausted".equals(mapped.code()))) {
            // Only a code rejection may commit its failed-attempt update.
            return new RegistrationSmsVerificationRejectedException(mapped);
        }
        return mapped;
    }

    private RuntimeException identityConflict(DataIntegrityViolationException error) {
        if (repositories.violatesUniqueConstraint(error, "uq_app_user_phone_lookup_hash")) {
            return new SmsApiException(HttpStatus.CONFLICT, "phone_unavailable");
        }
        if (repositories.violatesUniqueConstraint(error, "uq_app_user_email")) {
            return new ResponseStatusException(HttpStatus.CONFLICT, "email is already registered");
        }
        return new ResponseStatusException(HttpStatus.CONFLICT, "identity conflict");
    }

    private record Invitation(UUID id, String status, Instant expiresAt) {
    }

    public record UserProfile(
            UUID id,
            UUID tenantId,
            String email,
            String displayName,
            String role,
            boolean passwordSet,
            String status,
            @JsonIgnore long authVersion) {

        public UserProfile(UUID id, UUID tenantId, String email, String displayName, String role) {
            this(id, tenantId, email, displayName, role, true, "ACTIVE", 0L);
        }

        public UserProfile(
                UUID id, UUID tenantId, String email, String displayName, String role,
                boolean passwordSet) {
            this(id, tenantId, email, displayName, role, passwordSet, "ACTIVE", 0L);
        }

        public UserProfile(
                UUID id, UUID tenantId, String email, String displayName, String role,
                boolean passwordSet, long authVersion) {
            this(id, tenantId, email, displayName, role, passwordSet, "ACTIVE", authVersion);
        }

        public UserProfile(
                UUID id, UUID tenantId, String email, String displayName, String role,
                long authVersion) {
            this(id, tenantId, email, displayName, role, true, "ACTIVE", authVersion);
        }
    }

    public record AccessSession(
            String accessToken,
            long expiresIn,
            String refreshToken,
            Instant refreshExpiresAt,
            String csrfToken,
            UserProfile user) {
    }

    public record IdentityLoginResult(
            LoginHandoffService.LoginResult loginResult, boolean created,
            UUID pendingChallengeId, Instant pendingExpiresAt) {
        public IdentityLoginResult(LoginHandoffService.LoginResult loginResult, boolean created) {
            this(loginResult, created, null, null);
        }

        public static IdentityLoginResult pending(UUID challengeId, Instant expiresAt) {
            return new IdentityLoginResult(null, false, challengeId, expiresAt);
        }

        public boolean registrationPending() {
            return pendingChallengeId != null;
        }
    }

    public record AccountState(String status, String disabledReason, Instant disabledAt) { }

    public record SessionView(
            UUID id, String status, Instant createdAt, Instant expiresAt, Instant revokedAt) {
    }

    public record AvatarContent(byte[] bytes, String contentType) {
    }

    public record ProfileView(
            UUID id, UUID tenantId, String userNo, String email, String displayName, String status,
            Instant lastLoginAt, Instant createdAt, String maskedPhone,
            boolean phoneVerified, String phoneState, boolean smsNotificationsEnabled,
            boolean passwordSet, String avatarUrl, Instant avatarUpdatedAt,
            int maxLoginDevices, int activeDeviceCount, List<SessionView> sessions) {
    }
}
