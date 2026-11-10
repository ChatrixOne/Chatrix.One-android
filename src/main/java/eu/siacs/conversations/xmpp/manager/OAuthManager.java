package eu.siacs.conversations.xmpp.manager;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.google.common.primitives.Bytes;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.gson.annotations.SerializedName;
import de.gultsch.common.MiniUri;
import de.gultsch.common.TrustManagers;
import eu.siacs.conversations.BuildConfig;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.crypto.sasl.OAuthBearer;
import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.utils.Random;
import eu.siacs.conversations.utils.TLSSocketFactory;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import im.conversations.android.json.Services;
import java.io.IOException;
import java.io.Reader;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Credentials;
import okhttp3.Dns;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

public class OAuthManager extends AbstractManager {

    private static final Executor OAUTH_PREPERATION_EXECUTOR = Executors.newSingleThreadExecutor();

    public static final String OAUTH_REDIRECT_HOST = "oauth-redirect";

    private record OpenIdConfiguration(
            HttpUrl issuer,
            @SerializedName("authorization_endpoint") HttpUrl authorizationEndpoint,
            @SerializedName("token_endpoint") HttpUrl tokenEndpoint,
            @SerializedName("userinfo_endpoint") HttpUrl userinfoEndpoint,
            @SerializedName("revocation_endpoint") HttpUrl revocationEndpoint,
            @SerializedName("introspection_endpoint") HttpUrl introspectionEndpoint,
            @SerializedName("device_authorization_endpoint") HttpUrl deviceAuthorizationEndpoint,
            @SerializedName("registration_endpoint") HttpUrl registrationEndpoint,
            @SerializedName("service_documentation") HttpUrl serviceDocumentation,
            @SerializedName("authorization_response_iss_parameter_supported")
                    boolean authorizationResponseIssParameterSupported,
            @SerializedName("scopes_supported") List<String> scopesSupported,
            @SerializedName("response_types_supported") List<String> responseTypesSupported,
            @SerializedName("response_modes_supported") List<String> responseModesSupported,
            @SerializedName("grant_types_supported") List<String> grantTypesSupported,
            @SerializedName("code_challenge_methods_supported")
                    List<String> codeChallengeMethodsSupported,
            @SerializedName("token_endpoint_auth_methods_supported")
                    List<String> tokenEndpointAuthMethodsSupported,
            @SerializedName("revocation_endpoint_auth_methods_supported")
                    List<String> revocationEndpointAuthMethodsSupported,
            @SerializedName("id_token_signing_alg_values_supported")
                    List<String> idTokenSigningAlgValuesSupported) {}

    private record ClientRegistration(
            @SerializedName("client_name") String clientName,
            @SerializedName("client_uri") HttpUrl clientUri,
            @SerializedName("application_type") String applicationType,
            @SerializedName("redirect_uris") ImmutableSet<String> redirectUris) {}

    private record ClientRegistrationResponse(
            @SerializedName("client_id") String clientId,
            @SerializedName("client_secret") String clientSecret,
            @SerializedName("client_name") String clientName,
            @SerializedName("client_uri") String clientUri,
            @SerializedName("application_type") String applicationType,
            @SerializedName("token_endpoint_auth_method") String tokenEndpointAuthMethod,
            @SerializedName("redirect_uris") List<String> redirectUris,
            @SerializedName("grant_types") List<String> grantTypes,
            @SerializedName("response_types") List<String> responseTypes,
            @SerializedName("client_id_issued_at") Long clientIdIssuedAt,
            @SerializedName("client_secret_expires_at") Long clientSecretExpiresAt,
            Long iat,
            Long exp,
            String nonce) {}

    public record PendingAuthorization(
            HttpUrl authorizationUrl,
            String state,
            String codeVerifier,
            HttpUrl tokenEndpoint,
            String clientId,
            String clientSecret,
            String redirectUri,
            String authMethod) {}

    public record TokenResponse(
            @SerializedName("access_token") String accessToken,
            @SerializedName("token_type") String tokenType,
            @SerializedName("expires_in") Long expiresIn,
            @SerializedName("refresh_token") String refreshToken,
            String scope,
            @SerializedName("id_token") String idToken) {}

    private static final ClientRegistration DEFAULT_CLIENT_REGISTRATION =
            new ClientRegistration(
                    BuildConfig.APP_NAME,
                    new HttpUrl.Builder().scheme("https").host(Config.MAGIC_CREATE_DOMAIN).build(),
                    "native",
                    ImmutableSet.of(
                            String.format(
                                    "%s://%s", BuildConfig.APPLICATION_ID, OAUTH_REDIRECT_HOST)));

    private static final OkHttpClient OK_HTTP_CLIENT =
            HttpConnectionManager.OK_HTTP_CLIENT
                    .newBuilder()
                    .dns(hostname -> {
                        final var addresses = Dns.SYSTEM.lookup(hostname);
                        for (final var address : addresses) {
                            if (address.isLoopbackAddress()
                                    || address.isSiteLocalAddress()
                                    || address.isLinkLocalAddress()) {
                                throw new UnknownHostException(
                                        "Not performing look ups on local addresses");
                            }
                        }
                        return addresses;
                    })
                    .callTimeout(Duration.ofSeconds(3))
                    .build();

    private final Map<String, PendingAuthorization> pendingAuthorizations = new HashMap<>();

    public OAuthManager(final Context context, final XmppConnection connection) {
        super(context, connection);
    }

    public PendingAuthorization getPendingAuthorization() {
        synchronized (this.pendingAuthorizations) {
            return Iterables.getFirst(this.pendingAuthorizations.values(), null);
        }
    }

    public ListenableFuture<PendingAuthorization> preparePendingAuthorization(
            final OAuthBearer.Response response) {
        final var openIdConfigurationFuture = fetchConfiguration(response.openIdConfiguration());
        return Futures.transformAsync(
                openIdConfigurationFuture,
                config -> preparePendingAuthorization(Objects.requireNonNull(config)),
                OAUTH_PREPERATION_EXECUTOR);
    }

    private ListenableFuture<PendingAuthorization> preparePendingAuthorization(
            final OpenIdConfiguration openIdConfiguration) {
        final var registrationEndpoint = openIdConfiguration.registrationEndpoint();
        if (registrationEndpoint == null) {
            return Futures.immediateFailedFuture(
                    new IllegalStateException(
                            "Configuration did not contain registration endpoint"));
        }
        if (openIdConfiguration.authorizationEndpoint() == null) {
            return Futures.immediateFailedFuture(
                    new IllegalStateException(
                            "Configuration did not contain authorization endpoint"));
        }
        Log.d(Config.LOGTAG, "available scopes: " + openIdConfiguration.scopesSupported());
        final var registrationFuture = registerClient(registrationEndpoint);
        return Futures.transform(
                registrationFuture,
                registration -> {
                    final var pa =
                            buildAuthorizationUrl(
                                    getAccount().getJid().asBareJid(),
                                    openIdConfiguration,
                                    Objects.requireNonNull(registration));
                    synchronized (this.pendingAuthorizations) {
                        this.pendingAuthorizations.put(pa.state(), pa);
                    }
                    return pa;
                },
                OAUTH_PREPERATION_EXECUTOR);
    }

    private ListenableFuture<OpenIdConfiguration> fetchConfiguration(final HttpUrl url) {
        final var request = new Request.Builder().url(url).build();
        return Futures.transform(
                asFutureCharStream(request),
                cs -> Services.GSON.fromJson(Objects.requireNonNull(cs), OpenIdConfiguration.class),
                MoreExecutors.directExecutor());
    }

    private ListenableFuture<ClientRegistrationResponse> registerClient(final HttpUrl url) {
        final var request =
                new Request.Builder()
                        .url(url)
                        .post(
                                RequestBody.create(
                                        Services.GSON.toJson(DEFAULT_CLIENT_REGISTRATION),
                                        MediaType.get("application/json")))
                        .build();
        return Futures.transform(
                asFutureCharStream(request),
                cs ->
                        Services.GSON.fromJson(
                                Objects.requireNonNull(cs), ClientRegistrationResponse.class),
                MoreExecutors.directExecutor());
    }

    private PendingAuthorization buildAuthorizationUrl(
            final Jid account,
            final OpenIdConfiguration openIdConfiguration,
            final ClientRegistrationResponse registration) {
        final var stateBytes = new byte[32];
        Random.SECURE_RANDOM.nextBytes(stateBytes);
        final byte[] verifierBytes = new byte[32];
        Random.SECURE_RANDOM.nextBytes(verifierBytes);
        final var state =
                BaseEncoding.base64Url()
                        .omitPadding()
                        .encode(
                                Bytes.concat(
                                        account.toString().getBytes(StandardCharsets.UTF_8),
                                        new byte[] {0x00},
                                        stateBytes));
        final var codeVerifier = BaseEncoding.base64Url().omitPadding().encode(verifierBytes);

        final var digest = Hashing.sha256().hashString(codeVerifier, Charsets.US_ASCII).asBytes();
        final var codeChallenge = BaseEncoding.base64Url().omitPadding().encode(digest);
        if (Strings.isNullOrEmpty(registration.clientId())) {
            throw new IllegalStateException("Invalid client Id in registration");
        }
        if (Strings.isNullOrEmpty(registration.clientSecret())) {
            throw new IllegalStateException("Invalid client secret");
        }
        if (Strings.isNullOrEmpty(registration.tokenEndpointAuthMethod())) {
            throw new IllegalStateException("Invalid token endpoint auth method");
        }
        final var redirectUrl = Iterables.getOnlyElement(registration.redirectUris());
        final var authorizationUrl =
                buildAuthorizationUrl(
                        openIdConfiguration,
                        registration.clientId(),
                        redirectUrl,
                        state,
                        codeChallenge,
                        account.getLocal());
        return new PendingAuthorization(
                authorizationUrl,
                state,
                codeVerifier,
                openIdConfiguration.tokenEndpoint(),
                registration.clientId(),
                registration.clientSecret(),
                redirectUrl,
                registration.tokenEndpointAuthMethod());
    }

    private HttpUrl buildAuthorizationUrl(
            final OpenIdConfiguration openIdConfig,
            final String clientId,
            final String redirectUri,
            final String state,
            final String codeChallenge,
            final String username) {
        final var authorizationEndpoint = openIdConfig.authorizationEndpoint();
        if (authorizationEndpoint == null) {
            throw new IllegalStateException("No authorization endpoint in OpenId config");
        }
        final var builder = openIdConfig.authorizationEndpoint().newBuilder();
        return builder.addQueryParameter("response_type", "code")
                .addQueryParameter("client_id", clientId)
                .addQueryParameter("redirect_uri", redirectUri)
                .addQueryParameter("scope", "openid")
                .addQueryParameter("state", state)
                .addQueryParameter("code_challenge", codeChallenge)
                .addQueryParameter("code_challenge_method", "S256")
                .addQueryParameter("prompt", "login")
                .addQueryParameter("login_hint", username)
                .build();
    }

    public ListenableFuture<TokenResponse> processOauthRedirect(final MiniUri.Application uri) {
        final var code = uri.getParameter("code");
        final var state = uri.getParameter("state");
        final var account = uri.getAccount();
        if (Strings.isNullOrEmpty(state) || account == null) {
            return Futures.immediateFailedFuture(
                    new IllegalStateException("state missing or invalid"));
        }
        if (!this.getAccount().getJid().asBareJid().equals(account)) {
            return Futures.immediateFailedFuture(
                    new IllegalStateException(
                            "Could not handle oauth redirect. account did not match state"));
        }
        final PendingAuthorization pendingAuthorization;
        synchronized (this.pendingAuthorizations) {
            pendingAuthorization = this.pendingAuthorizations.remove(state);
        }
        if (pendingAuthorization == null) {
            return Futures.immediateFailedFuture(
                    new IllegalStateException("no pending authorization found"));
        }
        if (Strings.isNullOrEmpty(code)) {
            final var error = uri.getParameter("error");
            final var errorDescription = uri.getParameter("error_description");
            if (Strings.isNullOrEmpty(error) || Strings.isNullOrEmpty(errorDescription)) {
                return Futures.immediateFailedFuture(new IllegalStateException("Code is empty"));
            } else {
                return Futures.immediateFailedFuture(
                        new OAuthFailedException(error, errorDescription));
            }
        } else {
            final var future = this.exchangeAuthorizationCode(pendingAuthorization, code);
            future.addListener(
                    () -> {
                        synchronized (this.pendingAuthorizations) {
                            this.pendingAuthorizations.clear();
                        }
                    },
                    MoreExecutors.directExecutor());
            return future;
        }
    }

    public ListenableFuture<TokenResponse> exchangeAuthorizationCode(
            final PendingAuthorization pendingAuth, final String authCode) {
        final var formBuilder =
                new FormBody.Builder()
                        .add("grant_type", "authorization_code")
                        .add("code", authCode)
                        .add("redirect_uri", pendingAuth.redirectUri())
                        .add("code_verifier", pendingAuth.codeVerifier());

        final var requestBuilder =
                new okhttp3.Request.Builder()
                        .url(pendingAuth.tokenEndpoint())
                        .header("Accept", "application/json");

        if ("client_secret_basic".equals(pendingAuth.authMethod())) {
            String credentials =
                    Credentials.basic(
                            pendingAuth.clientId(),
                            pendingAuth.clientSecret() != null ? pendingAuth.clientSecret() : "");
            requestBuilder.header("Authorization", credentials);
        } else if ("client_secret_post".equals(pendingAuth.authMethod())) {
            formBuilder.add("client_id", pendingAuth.clientId());
            if (pendingAuth.clientSecret() != null) {
                formBuilder.add("client_secret", pendingAuth.clientSecret());
            }
        } else {
            formBuilder.add("client_id", pendingAuth.clientId());
        }

        final var request = requestBuilder.post(formBuilder.build()).build();
        return Futures.transform(
                asFutureCharStream(request),
                cs -> Services.GSON.fromJson(Objects.requireNonNull(cs), TokenResponse.class),
                MoreExecutors.directExecutor());
    }

    private ListenableFuture<Reader> asFutureCharStream(final Request request) {
        final OkHttpClient.Builder builder = OK_HTTP_CLIENT.newBuilder();
        try {
            final X509TrustManager trustManager = TrustManagers.createForAndroidVersion(context);
            final SSLSocketFactory socketFactory =
                    new TLSSocketFactory(new X509TrustManager[] {trustManager}, context);
            builder.sslSocketFactory(socketFactory, trustManager);
        } catch (final KeyManagementException
                | NoSuchAlgorithmException
                | KeyStoreException
                | CertificateException
                | IOException e) {
            return Futures.immediateFailedFuture(e);
        }
        final var okHttp = builder.build();
        final var future = SettableFuture.<Reader>create();
        final var call = okHttp.newCall(request);
        call.enqueue(
                new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        future.setException(e);
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull okhttp3.Response response) {
                        try (response) {
                            if (response.isSuccessful()) {
                                future.set(response.body().charStream());
                            } else {
                                future.setException(
                                        new IOException(
                                                String.format(
                                                        Locale.ROOT,
                                                        "Unexpected code %d",
                                                        response.code())));
                            }
                        } catch (final Exception e) {
                            future.setException(e);
                        }
                    }
                });
        return future;
    }

    public static final class OAuthFailedException extends Exception {
        public OAuthFailedException(final String error, final String errorDescription) {
            super(String.format("OAuth failed with %s (%s)", error, errorDescription));
        }
    }
}
