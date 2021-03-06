package org.usergrid.security.tokens.cassandra;

import static java.lang.System.currentTimeMillis;
import static me.prettyprint.hector.api.factory.HFactory.createColumn;
import static me.prettyprint.hector.api.factory.HFactory.createMutator;
import static org.apache.commons.codec.binary.Base64.decodeBase64;
import static org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString;
import static org.apache.commons.codec.digest.DigestUtils.sha;
import static org.usergrid.persistence.cassandra.CassandraPersistenceUtils.getColumnMap;
import static org.usergrid.persistence.cassandra.CassandraService.TOKENS_CF;
import static org.usergrid.security.tokens.TokenCategory.ACCESS;
import static org.usergrid.security.tokens.TokenCategory.EMAIL;
import static org.usergrid.security.tokens.TokenCategory.OFFLINE;
import static org.usergrid.security.tokens.TokenCategory.REFRESH;
import static org.usergrid.utils.ConversionUtils.bytebuffer;
import static org.usergrid.utils.ConversionUtils.bytes;
import static org.usergrid.utils.ConversionUtils.getLong;
import static org.usergrid.utils.ConversionUtils.string;
import static org.usergrid.utils.ConversionUtils.uuid;
import static org.usergrid.utils.MapUtils.hasKeys;
import static org.usergrid.utils.MapUtils.hashMap;
import static org.usergrid.utils.UUIDUtils.getTimestampInMillis;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import me.prettyprint.cassandra.serializers.ByteBufferSerializer;
import me.prettyprint.cassandra.serializers.LongSerializer;
import me.prettyprint.cassandra.serializers.StringSerializer;
import me.prettyprint.cassandra.serializers.UUIDSerializer;
import me.prettyprint.hector.api.beans.HColumn;
import me.prettyprint.hector.api.mutation.Mutator;

import org.mortbay.log.Log;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.usergrid.persistence.cassandra.CassandraService;
import org.usergrid.security.AuthPrincipalInfo;
import org.usergrid.security.AuthPrincipalType;
import org.usergrid.security.tokens.TokenCategory;
import org.usergrid.security.tokens.TokenInfo;
import org.usergrid.security.tokens.TokenService;
import org.usergrid.security.tokens.exceptions.BadTokenException;
import org.usergrid.security.tokens.exceptions.ExpiredTokenException;
import org.usergrid.security.tokens.exceptions.InvalidTokenException;
import org.usergrid.utils.JsonUtils;
import org.usergrid.utils.UUIDUtils;

public class TokenServiceImpl implements TokenService {

	public static final String PROPERTIES_AUTH_TOKEN_SECRET_SALT = "usergrid.auth.token_secret_salt";
	public static final String PROPERTIES_AUTH_TOKEN_EXPIRES_FROM_LAST_USE = "usergrid.auth.token_expires_from_last_use";
	public static final String PROPERTIES_AUTH_TOKEN_REFRESH_REUSES_ID = "usergrid.auth.token_refresh_reuses_id";

	private static final String TOKEN_UUID = "uuid";
	private static final String TOKEN_TYPE = "type";
	private static final String TOKEN_CREATED = "created";
	private static final String TOKEN_ACCESSED = "accessed";
	private static final String TOKEN_INACTIVE = "inactive";
	private static final String TOKEN_PRINCIPAL_TYPE = "principal";
	private static final String TOKEN_ENTITY = "entity";
	private static final String TOKEN_APPLICATION = "application";
	private static final String TOKEN_STATE = "state";

	private static final String TOKEN_TYPE_ACCESS = "access";

	private static final HashSet<String> TOKEN_PROPERTIES = new HashSet<String>();

	static {
		TOKEN_PROPERTIES.add(TOKEN_UUID);
		TOKEN_PROPERTIES.add(TOKEN_TYPE);
		TOKEN_PROPERTIES.add(TOKEN_CREATED);
		TOKEN_PROPERTIES.add(TOKEN_ACCESSED);
		TOKEN_PROPERTIES.add(TOKEN_INACTIVE);
		TOKEN_PROPERTIES.add(TOKEN_PRINCIPAL_TYPE);
		TOKEN_PROPERTIES.add(TOKEN_ENTITY);
		TOKEN_PROPERTIES.add(TOKEN_APPLICATION);
		TOKEN_PROPERTIES.add(TOKEN_STATE);
	}

	private static final HashSet<String> REQUIRED_TOKEN_PROPERTIES = new HashSet<String>();

	static {
		REQUIRED_TOKEN_PROPERTIES.add(TOKEN_UUID);
		REQUIRED_TOKEN_PROPERTIES.add(TOKEN_TYPE);
		REQUIRED_TOKEN_PROPERTIES.add(TOKEN_CREATED);
		REQUIRED_TOKEN_PROPERTIES.add(TOKEN_ACCESSED);
		REQUIRED_TOKEN_PROPERTIES.add(TOKEN_INACTIVE);
	}

	public static final String TOKEN_SECRET_SALT = "super secret token value";

	// Short-lived token is good for 24 hours
	public static final long SHORT_TOKEN_AGE = 24 * 60 * 60 * 1000;

	// Long-lived token is good for 7 days
	public static final long LONG_TOKEN_AGE = 7 * 24 * 60 * 60 * 1000;

	String tokenSecretSalt = TOKEN_SECRET_SALT;

	long maxPersistenceTokenAge = LONG_TOKEN_AGE;

	Map<TokenCategory, Long> tokenExpirations = hashMap(ACCESS, SHORT_TOKEN_AGE)
			.map(REFRESH, LONG_TOKEN_AGE).map(EMAIL, LONG_TOKEN_AGE)
			.map(OFFLINE, LONG_TOKEN_AGE);

	long maxAccessTokenAge = SHORT_TOKEN_AGE;
	long maxRefreshTokenAge = LONG_TOKEN_AGE;
	long maxEmailTokenAge = LONG_TOKEN_AGE;
	long maxOfflineTokenAge = LONG_TOKEN_AGE;

	protected CassandraService cassandra;

	protected Properties properties;

	public TokenServiceImpl() {

	}

	long getExpirationProperty(String name, long default_expiration) {
		long expires = Long.parseLong(properties.getProperty(
				"usergrid.auth.token." + name + ".expires", ""
						+ default_expiration));
		return expires > 0 ? expires : default_expiration;
	}

	long getExpirationForTokenType(TokenCategory tokenCategory) {
		Long l = tokenExpirations.get(tokenCategory);
		if (l != null) {
			return l;
		}
		return SHORT_TOKEN_AGE;
	}

	void setExpirationFromProperties(String name) {
		TokenCategory tokenCategory = TokenCategory.valueOf(name.toUpperCase());
		long expires = Long.parseLong(properties.getProperty(
				"usergrid.auth.token." + name + ".expires", ""
						+ getExpirationForTokenType(tokenCategory)));
		if (expires > 0) {
			tokenExpirations.put(tokenCategory, expires);
		}
		Log.info(name + " token expires after "
				+ getExpirationForTokenType(tokenCategory) / 1000 + " seconds");
	}

	@Autowired
	public void setProperties(Properties properties) {
		this.properties = properties;

		if (properties != null) {
			maxPersistenceTokenAge = getExpirationProperty("persistence",
					maxPersistenceTokenAge);

			setExpirationFromProperties("access");
			setExpirationFromProperties("refresh");
			setExpirationFromProperties("email");
			setExpirationFromProperties("offline");

			tokenSecretSalt = properties.getProperty(
					PROPERTIES_AUTH_TOKEN_SECRET_SALT, TOKEN_SECRET_SALT);
		}
	}

	@Autowired
	@Qualifier("cassandraService")
	public void setCassandraService(CassandraService cassandra) {
		this.cassandra = cassandra;
	}

	@Override
	public String createToken(TokenCategory tokenCategory, String type,
			Map<String, Object> state) throws Exception {
		return createToken(tokenCategory, type, null, state);
	}

	@Override
	public String createToken(AuthPrincipalInfo principal) throws Exception {
		return createToken(TokenCategory.ACCESS, null, principal, null);
	}

	@Override
	public String createToken(AuthPrincipalInfo principal,
			Map<String, Object> state) throws Exception {
		return createToken(TokenCategory.ACCESS, null, principal, state);
	}

	@Override
	public String createToken(TokenCategory tokenCategory, String type,
			AuthPrincipalInfo principal, Map<String, Object> state)
			throws Exception {
		UUID uuid = UUIDUtils.newTimeUUID();
		long timestamp = getTimestampInMillis(uuid);
		if (type == null) {
			type = TOKEN_TYPE_ACCESS;
		}
		TokenInfo tokenInfo = new TokenInfo(uuid, type, timestamp, timestamp,
				0, principal, state);
		putTokenInfo(tokenInfo);
		return getTokenForUUID(tokenCategory, uuid);
	}

	@Override
	public TokenInfo getTokenInfo(String token) throws Exception {
		TokenInfo tokenInfo = null;
		UUID uuid = getUUIDForToken(token);
		if (uuid != null) {
			tokenInfo = getTokenInfo(uuid);
			if (tokenInfo != null) {
				long now = currentTimeMillis();

				Mutator<UUID> batch = createMutator(
						cassandra.getSystemKeyspace(), UUIDSerializer.get());

				HColumn<String, Long> col = createColumn(TOKEN_ACCESSED, now,
						(int) (maxPersistenceTokenAge / 1000),
						StringSerializer.get(), LongSerializer.get());
				batch.addInsertion(uuid, TOKENS_CF, col);

				long inactive = now - tokenInfo.getAccessed();
				if (inactive > tokenInfo.getInactive()) {
					col = createColumn(TOKEN_INACTIVE, inactive,
							(int) (maxPersistenceTokenAge / 1000),
							StringSerializer.get(), LongSerializer.get());
					batch.addInsertion(uuid, TOKENS_CF, col);
					tokenInfo.setInactive(inactive);
				}

				batch.execute();
			}
		}
		return tokenInfo;
	}

	@Override
	public String refreshToken(String token) throws Exception {
		TokenInfo tokenInfo = getTokenInfo(getUUIDForToken(token));
		if (tokenInfo != null) {
			putTokenInfo(tokenInfo);
			return getTokenForUUID(TokenCategory.ACCESS, tokenInfo.getUuid());
		}
		throw new InvalidTokenException("Token not found in database");
	}

	public TokenInfo getTokenInfo(UUID uuid) throws Exception {
		if (uuid == null) {
			throw new InvalidTokenException("No token specified");
		}
		Map<String, ByteBuffer> columns = getColumnMap(cassandra.getColumns(
				cassandra.getSystemKeyspace(), TOKENS_CF, uuid,
				TOKEN_PROPERTIES, StringSerializer.get(),
				ByteBufferSerializer.get()));
		if (!hasKeys(columns, REQUIRED_TOKEN_PROPERTIES)) {
			throw new InvalidTokenException("Token not found in database");
		}
		String type = string(columns.get(TOKEN_TYPE));
		long created = getLong(columns.get(TOKEN_CREATED));
		long accessed = getLong(columns.get(TOKEN_ACCESSED));
		long inactive = getLong(columns.get(TOKEN_INACTIVE));
		String principalTypeStr = string(columns.get(TOKEN_PRINCIPAL_TYPE));
		AuthPrincipalType principalType = null;
		if (principalTypeStr != null) {
			try {
				principalType = AuthPrincipalType.valueOf(principalTypeStr
						.toUpperCase());
			} catch (IllegalArgumentException e) {
			}
		}
		AuthPrincipalInfo principal = null;
		if (principalType != null) {
			UUID entityId = uuid(columns.get(TOKEN_ENTITY));
			UUID appId = uuid(columns.get(TOKEN_APPLICATION));
			principal = new AuthPrincipalInfo(principalType, entityId, appId);
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> state = (Map<String, Object>) JsonUtils
				.fromByteBuffer(columns.get(TOKEN_STATE));
		return new TokenInfo(uuid, type, created, accessed, inactive,
				principal, state);
	}

	public void putTokenInfo(TokenInfo tokenInfo) throws Exception {
		Map<String, ByteBuffer> columns = new HashMap<String, ByteBuffer>();
		columns.put(TOKEN_UUID, bytebuffer(tokenInfo.getUuid()));
		columns.put(TOKEN_TYPE, bytebuffer(tokenInfo.getType()));
		columns.put(TOKEN_CREATED, bytebuffer(tokenInfo.getCreated()));
		columns.put(TOKEN_ACCESSED, bytebuffer(tokenInfo.getAccessed()));
		columns.put(TOKEN_INACTIVE, bytebuffer(tokenInfo.getInactive()));
		if (tokenInfo.getPrincipal() != null) {
			columns.put(TOKEN_PRINCIPAL_TYPE, bytebuffer(tokenInfo
					.getPrincipal().getType().toString().toLowerCase()));
			columns.put(TOKEN_ENTITY, bytebuffer(tokenInfo.getPrincipal()
					.getUuid()));
			columns.put(TOKEN_APPLICATION, bytebuffer(tokenInfo.getPrincipal()
					.getApplicationId()));
		}
		columns.put(TOKEN_STATE, JsonUtils.toByteBuffer(tokenInfo.getState()));
		cassandra.setColumns(cassandra.getSystemKeyspace(), TOKENS_CF,
				bytes(tokenInfo.getUuid()), columns,
				(int) (maxPersistenceTokenAge / 1000));
	}

	public UUID getUUIDForToken(String token) throws ExpiredTokenException,
			BadTokenException {
		TokenCategory tokenCategory = TokenCategory.getFromBase64String(token);
		byte[] bytes = decodeBase64(token
				.substring(TokenCategory.BASE64_PREFIX_LENGTH));
		UUID uuid = uuid(bytes);
		long timestamp = getTimestampInMillis(uuid);
		if ((getExpirationForTokenType(tokenCategory) > 0)
				&& (currentTimeMillis() > (timestamp + getExpirationForTokenType(tokenCategory)))) {
			throw new ExpiredTokenException(
					"Token expired "
							+ (currentTimeMillis() - (timestamp + getExpirationForTokenType(tokenCategory)))
							+ " millisecons ago.");
		}
		int i = 16;
		long expires = Long.MAX_VALUE;
		if (tokenCategory.getExpires()) {
			expires = ByteBuffer.wrap(bytes, i, 8).getLong();
			i = 24;
		}
		ByteBuffer expected = ByteBuffer.allocate(20);
		expected.put(sha(tokenCategory.getPrefix() + uuid + tokenSecretSalt
				+ expires));
		expected.rewind();
		ByteBuffer signature = ByteBuffer.wrap(bytes, i, 20);
		if (!signature.equals(expected)) {
			throw new BadTokenException("Invalid token signature");
		}
		return uuid;
	}

	@Override
	public long getMaxTokenAge(String token) {
		TokenCategory tokenCategory = TokenCategory.getFromBase64String(token);
		byte[] bytes = decodeBase64(token
				.substring(TokenCategory.BASE64_PREFIX_LENGTH));
		UUID uuid = uuid(bytes);
		long timestamp = getTimestampInMillis(uuid);
		int i = 16;
		if (tokenCategory.getExpires()) {
			long expires = ByteBuffer.wrap(bytes, i, 8).getLong();
			return expires - timestamp;
		}
		return Long.MAX_VALUE;
	}

	public String getTokenForUUID(TokenCategory tokenCategory, UUID uuid) {
		int l = 36;
		if (tokenCategory.getExpires()) {
			l += 8;
		}
		ByteBuffer bytes = ByteBuffer.allocate(l);
		bytes.put(bytes(uuid));
		long expires = Long.MAX_VALUE;
		if (tokenCategory.getExpires()) {
			expires = UUIDUtils.getTimestampInMillis(uuid)
					+ getExpirationForTokenType(tokenCategory);
			bytes.putLong(expires);
		}
		bytes.put(sha(tokenCategory.getPrefix() + uuid + tokenSecretSalt
				+ expires));
		return tokenCategory.getBase64Prefix()
				+ encodeBase64URLSafeString(bytes.array());
	}

}
