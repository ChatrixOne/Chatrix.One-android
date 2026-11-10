package eu.siacs.conversations.crypto.sasl;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.utils.CryptoHelper;
import im.conversations.android.json.Services;
import javax.net.ssl.SSLSocket;
import okhttp3.HttpUrl;

public class OAuthBearer extends SaslMechanism {

    private static final String STATUS_INVALID_TOKEN = "invalid_token";

    public record Response(
            String status,
            String scope,
            @SerializedName("openid-configuration") HttpUrl openIdConfiguration) {}

    public static final String MECHANISM = "OAUTHBEARER";

    protected OAuthBearer(final Account account) {
        super(account);
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public String getMechanism() {
        return MECHANISM;
    }

    @Override
    public byte[] getClientFirstMessage(final SSLSocket sslSocket) {
        Preconditions.checkState(
                this.state == State.INITIAL, "Calling getClientFirstMessage from invalid state");
        this.state = State.AUTH_TEXT_SENT;
        final var oauthAccessToken = this.account.getOauthAccessToken();
        final var a = CryptoHelper.saslEscape(CryptoHelper.saslPrep(account.getUsername()));

        final String message;
        if (Strings.isNullOrEmpty(oauthAccessToken)) {
            message = String.format("n,a=%s,\1auth=\1\1", a);
        } else {
            message = String.format("n,a=%s,\1auth=Bearer %s\1\1", a, oauthAccessToken);
        }
        return message.getBytes();
    }

    @Override
    public byte[] getResponse(final byte[] challenge, final SSLSocket sslSocket)
            throws AuthenticationException {
        checkState(State.AUTH_TEXT_SENT);
        if (challenge.length == 0) {
            this.state = State.VALID_SERVER_RESPONSE;
            return new byte[0];
        }
        final OAuthBearer.Response response;
        try {
            response = Services.GSON.fromJson(new String(challenge), Response.class);
        } catch (final JsonSyntaxException e) {
            throw new AuthenticationException(e);
        }
        if (response.openIdConfiguration() == null) {
            throw new AuthenticationException("SASL response did not contain OpenId configuration");
        }
        if (STATUS_INVALID_TOKEN.equals(response.status())) {
            throw new InvalidTokenException(response);
        } else {
            throw new AuthenticationException(
                    String.format("Unrecognized OAuth status %s", response.status));
        }
    }

    public static class InvalidTokenException extends AuthenticationException {
        private final Response response;

        public InvalidTokenException(final OAuthBearer.Response response) {
            super("OAuth authentication failed");
            this.response = response;
        }

        public Response getResponse() {
            return this.response;
        }
    }
}
