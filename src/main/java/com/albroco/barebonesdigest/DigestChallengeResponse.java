package com.albroco.barebonesdigest;

import android.annotation.SuppressLint;

import com.albroco.barebonesdigest.DigestChallenge.QualityOfProtection;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.Set;

;

/**
 * Describes the contents of an {@code Authorization} HTTP request header. Once the client has
 * received a HTTP Digest challenge from the server this header should be included in all subsequent
 * requests to authorize the client.
 * <p>
 * Instances of this class is normally created as a response to an incoming challenge using
 * {@link #responseTo(DigestChallenge)}. To generate the {@code Authorization} header, som
 * additional values must be set:
 * <ul>
 * <li>The {@link #username(String) username} and {@link #password(String) password} for
 * authentication.</li>
 * <li>The {@link #digestUri(String) digestUri} used in the HTTP request.</li>
 * <li>The {@link #requestMethod(String) request method} of the request, such as "GET" or "POST".
 * </li>
 * </ul>
 * <p>
 *
 * Here is an example of how to create a response:
 * <pre>
 * {@code
 * DigestChallengeResponse response = DigestChallengeResponse.responseTo(challenge)
 *                                                           .username("user")
 *                                                           .password("passwd")
 *                                                           .digestUri("/example")
 *                                                           .requestMethod("GET");
 * }
 * </pre>
 *
 * <h2>Challenge response reuse (optional)</h2>
 *
 * A challenge response from an earlier challenge can be reused in subsequent requests. If the
 * server accepts the reused challenge this will cut down on unnecessary traffic.
 * <p>
 * Each time the challenge response is reused the nonce count must be increased by one, see
 * {@link #incrementNonceCount()}. It is also a good idea to generate a new random client nonce with
 * {@link #randomizeClientNonce()}:
 * <pre>
 * {@code
 * response.incrementNonceCount().randomizeClientNonce(); // Response is now ready for reuse
 * }
 * </pre>
 *
 * <h2>Overriding the default client nonce (not recommended)</h2>
 *
 * The client nonce is a random string set by the client that is included in the challenge response.
 * By default, a random string is generated for the client nonce using {@code
 * java.security.SecureRandom}, which should be suitable for most purposes.
 * <p>
 * If you still for some reason need to override the default client nonce you can set it using
 * {@link #clientNonce(String)}. You may also have to call {@link #firstRequestClientNonce(String)},
 * see the documentation of thet method for details.
 *
 * <h2>Limitations</h2>
 *
 * <ul>
 * <li>Quality of protection (qop) {@code auth-int} is not supported.</li>
 * </ul>
 *
 * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">RFC 2617, "HTTP Digest Access
 * Authentication", Section 3.2.2, "The Authorization Request Header"</a>
 */
public class DigestChallengeResponse {
  /**
   * The name of the HTTP request header ({@value #HTTP_HEADER_AUTHORIZATION}).
   */
  public static final String HTTP_HEADER_AUTHORIZATION = "Authorization";

  private static final int CLIENT_NONCE_BYTE_COUNT = 8;
  @SuppressLint("TrulyRandom")
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final byte[] clientNonceByteBuffer = new byte[CLIENT_NONCE_BYTE_COUNT];

  private final MessageDigest md5;

  private String algorithm;
  private String username;
  private String password;
  private String clientNonce;
  private String firstRequestClientNonce;
  private String quotedNonce;
  private int nonceCount;
  private String quotedOpaque;
  private Set<DigestChallenge.QualityOfProtection> supportedQopTypes;
  private String digestUri;
  private String quotedRealm;
  private String requestMethod;
  private String A1;

  /**
   * Creates an empty challenge response.
   * <p>
   * Consider using {@link #responseTo(DigestChallenge)} when creating a response to a specific
   * challenge.
   */
  public DigestChallengeResponse() {
    try {
      this.md5 = MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException e) {
      // TODO find out if this can happen
      throw new RuntimeException(e);
    }

    supportedQopTypes = EnumSet.noneOf(QualityOfProtection.class);
    nonceCount(1).randomizeClientNonce().firstRequestClientNonce(getClientNonce());
  }

  /**
   * Creates a digest challenge response, setting the values of the {@code realm}, {@code nonce},
   * {@code opaque}, and {@code algorithm} directives and the supported quality of protection
   * types based on a challenge.
   *
   * @param challenge the challenge
   * @return a response to the challenge.
   * @throws UnsupportedAlgorithmHttpDigestException if the challenge uses an algorithm that is
   *                                                 not supported
   */
  public static DigestChallengeResponse responseTo(DigestChallenge challenge) throws
      UnsupportedAlgorithmHttpDigestException {
    return new DigestChallengeResponse().challenge(challenge);
  }

  /**
   * Sets the {@code algorithm} directive, which must be the same as the {@code algorithm} directive
   * of the challenge. The only values currently supported are "MD5" and "MD5-sess".
   *
   * @param algorithm the value of the {@code algorithm} directive or {@code null} to not include an
   *                  algorithm in the response
   * @return this object so that setters can be chained
   * @throws UnsupportedAlgorithmHttpDigestException if the algorithm is not supported
   * @see #getAlgorithm()
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">Section 3.2.2 of RFC 2617</a>
   */
  public DigestChallengeResponse algorithm(String algorithm) throws
      UnsupportedAlgorithmHttpDigestException {
    if (algorithm != null && !"MD5".equals(algorithm) && !"MD5-sess".equals(algorithm)) {
      throw new UnsupportedAlgorithmHttpDigestException("Unsupported algorithm: " + algorithm);
    }

    this.algorithm = algorithm;
    invalidateA1();
    return this;
  }

  /**
   * Returns the value of the {@code algorithm} directive.
   *
   * @return the value of the {@code algorithm} directive
   * @see #algorithm(String)
   */
  public String getAlgorithm() {
    return algorithm;
  }

  /**
   * Sets the username to use for authentication.
   *
   * @param username the username
   * @return this object so that setters can be chained
   * @see #getUsername()
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">Section 3.2.2 of RFC 2617</a>
   */
  public DigestChallengeResponse username(String username) {
    this.username = username;
    invalidateA1();
    return this;
  }

  /**
   * Returns the username to use for authentication.
   *
   * @return the username
   * @see #username(String)
   */
  public String getUsername() {
    return username;
  }

  /**
   * Sets the password to use for authentication.
   *
   * @param password the password
   * @return this object so that setters can be chained
   * @see #getPassword()
   */
  public DigestChallengeResponse password(String password) {
    this.password = password;
    invalidateA1();
    return this;
  }

  /**
   * Returns the password to use for authentication.
   *
   * @return the password
   * @see #password(String)
   */
  public String getPassword() {
    return password;
  }

  /**
   * Sets the {@code cnonce} directive, which is a random string generated by the client that will
   * be included in the challenge response hash.
   * <p>
   * <b>There is normally no need to manually set the client nonce since it will have a default
   * value of a randomly generated string.</b> If you do, make sure to call
   * {@link #firstRequestClientNonce(String)} if you modify the client nonce for the first request,
   * or some algorithms may not work (notably {@code MD5-sess}).
   *
   * @param clientNonce The unquoted value of the {@code cnonce} directive.
   * @return this object so that setters can be chained
   * @see #getClientNonce()
   * @see #randomizeClientNonce()
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">Section 3.2.2 of RFC 2617</a>
   */
  public DigestChallengeResponse clientNonce(String clientNonce) {
    this.clientNonce = clientNonce;
    if ("MD5-sess".equals(getAlgorithm())) {
      invalidateA1();
    }
    return this;
  }

  /**
   * Returns the value of the {@code cnonce} directive.
   * <p>
   * Unless overridden by calling {@link #clientNonce(String)}, the {@code cnonce} directive is
   * set to a randomly generated string.
   *
   * @return the {@code cnonce} directive
   * @see #clientNonce(String)
   */
  public String getClientNonce() {
    return clientNonce;
  }

  /**
   * Sets the {@code cnonce} directive to a random value.
   *
   * @return this object so that setters can be chained
   * @see #clientNonce(String)
   * @see #getClientNonce()
   */
  public DigestChallengeResponse randomizeClientNonce() {
    return clientNonce(generateRandomNonce());
  }

  /**
   * Sets the value of client nonce used in the first request.
   * <p>
   * This value is used in some algorithms, notably {@code MD5-sess}. If the challenge is reused
   * for multiple request, the original client nonce used when responding to the original challenge
   * is used in subsequent challenge responses, even if the client changes the client nonce for
   * subsequent requests.
   * <p>
   * <b>Normally, there is no need to call this method.</b> The default value of the client nonce
   * is a randomly generated string, and the default value of the first request client nonce is
   * the same string. It is only if you override the default value and supply your own client
   * nonce for the first request that you must make sure to call this method with the same value:
   * <blockquote>
   * {@code
   * response,clientNocne("my own client nonce").firstRequestClientNonce(response.getClientNonce());
   * }
   * </blockquote>
   *
   * @param firstRequestClientNonce the client nonce value used in the first request
   * @return this object so that setters can be chained
   * @see #getFirstRequestClientNonce()
   * @see #clientNonce(String)
   * @see #getClientNonce()
   * @see #randomizeClientNonce()
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2.2">Section 3.2.2.2, A1, of RFC
   * 2617</a>
   */
  public DigestChallengeResponse firstRequestClientNonce(String firstRequestClientNonce) {
    this.firstRequestClientNonce = firstRequestClientNonce;
    if ("MD5-sess".equals(getAlgorithm())) {
      invalidateA1();
    }
    return this;
  }

  /**
   * Returns the value of client nonce used in the first request.
   * <p>
   * This value is used in some algorithms, notably {@code MD5-sess}. If the challenge is reused
   * for multiple request, the original client nonce used when responding to the original challenge
   * is used in subsequent challenge responses, even if the client changes the client nonce for
   * subsequent requests.
   *
   * @return the value of client nonce used in the first request.
   * @see #firstRequestClientNonce(String)
   * @see #clientNonce(String)
   * @see #getClientNonce()
   * @see #randomizeClientNonce()
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2.2">Section 3.2.2.2, A1, of RFC
   * 2617</a>
   */
  public String getFirstRequestClientNonce() {
    return firstRequestClientNonce;
  }

  /**
   * Sets the {@code nonce} directive, which must be the same as the nonce directive of the
   * challenge.
   * <p>
   * Setting the {@code nonce} directive resets the nonce count to one.
   *
   * @param quotedNonce the quoted value of the {@code nonce} directive
   * @return this object so that setters can be chained
   * @see #getQuotedNonce()
   * @see #nonce(String)
   * @see #getNonce()
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">Section 3.2.2 of RFC 2617</a>
   */
  public DigestChallengeResponse quotedNonce(String quotedNonce) {
    this.quotedNonce = quotedNonce;
    resetNonceCount();
    if ("MD5-sess".equals(getAlgorithm())) {
      invalidateA1();
    }
    return this;
  }

  /**
   * Returns the quoted value of the {@code nonce} directive.
   *
   * @return the quoted value of the {@code nonce} directive
   * @see #quotedNonce(String)
   * @see #nonce(String)
   * @see #getNonce()
   */
  public String getQuotedNonce() {
    return quotedNonce;
  }

  /**
   * Sets the {@code nonce} directive, which must be the same as the {@code nonce} directive of the
   * challenge.
   * <p>
   * Setting the nonce directive resets the nonce count to one.
   *
   * @param unquotedNonce the unquoted value of the {@code nonce} directive
   * @return this object so that setters can be chained
   * @see #getNonce()
   * @see #quotedNonce(String)
   * @see #getQuotedNonce()
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">Section 3.2.2 of RFC 2617</a>
   */
  public DigestChallengeResponse nonce(String unquotedNonce) {
    if (unquotedNonce == null) {
      return quotedNonce(null);
    }
    return quotedNonce(Rfc2616AbnfParser.quote(unquotedNonce));
  }

  /**
   * Returns the unquoted value of the {@code nonce} directive
   *
   * @return the unquoted value of the {@code nonce} directive
   * @see #nonce(String)
   * @see #quotedNonce(String)
   * @see #getQuotedNonce()
   */
  public String getNonce() {
    // TODO: Cache since value is used each time a header is written
    if (quotedNonce == null) {
      return null;
    }

    return Rfc2616AbnfParser.unquote(quotedNonce);
  }

  /**
   * Sets the integer representation of the {@code nonce-count} directive, which indicates how many
   * times this a challenge response with this nonce has been used.
   * <p>
   * This is useful when using a challenge response from a previous challenge when sending a
   * request. For each time a challenge response is used, the nonce count should be increased by
   * one.
   *
   * @param nonceCount integer representation of the {@code nonce-count} directive
   * @return this object so that setters can be chained
   * @see #getNonceCount()
   * @see #resetNonceCount()
   * @see #incrementNonceCount()
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">Section 3.2.2 of RFC 2617</a>
   */
  public DigestChallengeResponse nonceCount(int nonceCount) {
    this.nonceCount = nonceCount;
    return this;
  }

  /**
   * Increments the value of the {@code nonce-count} by one.
   *
   * @return this object so that setters can be chained
   * @see #nonceCount(int)
   * @see #getNonceCount()
   * @see #resetNonceCount()
   */
  public DigestChallengeResponse incrementNonceCount() {
    nonceCount(nonceCount + 1);
    return this;
  }

  /**
   * Sets the value of the {@code nonce-count} to one.
   *
   * @return this object so that setters can be chained
   * @see #nonceCount(int)
   * @see #getNonceCount()
   * @see #incrementNonceCount()
   */
  public DigestChallengeResponse resetNonceCount() {
    nonceCount(1);
    return this;
  }

  /**
   * Returns the integer representation of the {@code nonce-count} directive.
   *
   * @return the integer representation of the {@code nonce-count} directive
   * @see #nonceCount(int)
   * @see #resetNonceCount()
   * @see #incrementNonceCount()
   */
  public int getNonceCount() {
    return nonceCount;
  }


  /**
   * Sets the {@code opaque} directive, which must be the same as the {@code opaque} directive of
   * the challenge.
   *
   * @param quotedOpaque the quoted value of the {@code opaque} directive, or {@code null} if no
   *                     opaque directive should be included in the challenge response
   * @return this object so that setters can be chained
   * @see #getQuotedOpaque()
   * @see #opaque(String)
   * @see #getOpaque()
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">Section 3.2.2 of RFC 2617</a>
   */
  public DigestChallengeResponse quotedOpaque(String quotedOpaque) {
    this.quotedOpaque = quotedOpaque;
    return this;
  }

  /**
   * Returns the the quoted value of the {@code opaque} directive, or {@code null}.
   *
   * @return the the quoted value of the {@code opaque} directive, or {@code null} if the {@code
   * opaque} is not set
   * @see #quotedOpaque(String)
   * @see #opaque(String)
   * @see #getOpaque()
   */
  public String getQuotedOpaque() {
    return quotedOpaque;
  }

  /**
   * Sets the {@code opaque} directive, which must be the same as the {@code opaque} directive of
   * the challenge.
   * <p>
   * Note: Since the value of the {@code opaque} directive is always received from a challenge
   * quoted it is normally better to use the {@link #quotedOpaque(String)} method to avoid
   * unnecessary quoting/unquoting.
   *
   * @param unquotedOpaque the unquoted value of the {@code opaque} directive, or {@code null} if no
   *                       {@code opaque} directive should be included in the challenge response
   * @return this object so that setters can be chained
   * @see #getOpaque()
   * @see #quotedOpaque(String)
   * @see #getQuotedOpaque()
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">Section 3.2.2 of RFC 2617</a>
   */
  public DigestChallengeResponse opaque(String unquotedOpaque) {
    if (unquotedOpaque == null) {
      return quotedOpaque(null);
    }
    return quotedOpaque(Rfc2616AbnfParser.quote(unquotedOpaque));
  }

  /**
   * Returns the the unquoted value of the {@code opaque} directive, or {@code null}.
   *
   * @return the the unquoted value of the {@code opaque} directive, or {@code null} if the {@code
   * opaque} is not set
   * @see #opaque(String)
   * @see #quotedOpaque(String)
   * @see #getQuotedOpaque()
   */
  public String getOpaque() {
    if (quotedOpaque == null) {
      return null;
    }
    return Rfc2616AbnfParser.unquote(quotedOpaque);
  }

  /**
   * Sets the type of "quality of protection" that can be used when responding to the request.
   * <p>
   * Normally, this value is sent by the server in the challenge, but setting it manually can be
   * used to force a particular qop type. Actual qop type in the response is chosen as follows:
   * <ol>
   * <li>If {@link QualityOfProtection#AUTH} is supported it is used.</li>
   * <li>Otherwise, if {@link QualityOfProtection#UNSPECIFIED_RFC2069_COMPATIBLE} is supported it
   * is used.</li>
   * <li>Otherwise, there is no way to generate a response to the challenge without violating the
   * standard. As a fallback, use {@link QualityOfProtection#UNSPECIFIED_RFC2069_COMPATIBLE}.</li>
   * </ol>
   * Note that {@link QualityOfProtection#AUTH_INT} is not yet supported.
   *
   * @param supportedQopTypes the types of quality of protection that the server supports
   * @return this object so that setters can be chained
   * @see #getSupportedQopTypes()
   */
  public DigestChallengeResponse supportedQopTypes(Set<QualityOfProtection> supportedQopTypes) {
    this.supportedQopTypes.clear();
    this.supportedQopTypes.addAll(supportedQopTypes);
    return this;
  }

  /**
   * Returns the type of "quality of protection" that can be used when responding to the request.
   *
   * @return the types of quality of protection that the server supports
   * @see #supportedQopTypes(Set)
   */
  public Set<QualityOfProtection> getSupportedQopTypes() {
    return supportedQopTypes;
  }


  /**
   * Sets the {@code digest-uri} directive, which must be exactly the same as the
   * {@code Request-URI} of the {@code Request-Line} of the HTTP request.
   * <p>
   * The digest URI is explained in
   * <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">Section 3.2.2 of RFC 2617</a>,
   * and refers to the explanation of Request-URI found in
   * <a href="https://tools.ietf.org/html/rfc2616#section-5.1.2">Section 5.1.2 of RFC 2616</a>.
   * <p>
   * Examples: If the {@code Request-Line} is
   * <pre>
   * GET http://www.w3.org/pub/WWW/TheProject.html HTTP/1.1
   * </pre>
   * the {@code Request-URI} (and {@code digest-uri}) is "{@code
   * http://www.w3.org/pub/WWW/TheProject.html}". If {@code Request-Line} is
   * <pre>
   * GET /pub/WWW/TheProject.html HTTP/1.1
   * </pre>
   * the {@code Request-URI} is "{@code /pub/WWW/TheProject.html}".
   * <p>
   * This can be problematic since depending on the HTTP stack being used the {@code Request-Line}
   * and {@code Request-URI} values may not be accessible. If in doubt, a sensible guess is to set
   * the {@code digest-uri} to the path part of the URL being requested, for instance using
   * <a href="https://developer.android.com/reference/java/net/URL.html#getPath()">
   * <code>getPath()</code> in the <code>URL</code> class</a>.
   *
   * @param digestUri the value of the digest-uri directive
   * @return this object so that setters can be chained
   * @see #getDigestUri()
   */
  public DigestChallengeResponse digestUri(String digestUri) {
    this.digestUri = digestUri;
    return this;
  }

  /**
   * Returns the value of the {@code digest-uri} directive.
   *
   * @return the value of the {@code digest-uri} directive
   * @see #digestUri(String)
   */
  public String getDigestUri() {
    return digestUri;
  }

  /**
   * Sets the {@code realm} directive, which must be the same as the {@code realm} directive of
   * the challenge.
   *
   * @param quotedRealm the quoted value of the {@code realm} directive
   * @return this object so that setters can be chained
   * @see #getQuotedRealm()
   * @see #realm(String)
   * @see #getRealm()
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.1">Section 3.2.1 of RFC 2617</a>
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">Section 3.2.2 of RFC 2617</a>
   */
  public DigestChallengeResponse quotedRealm(String quotedRealm) {
    this.quotedRealm = quotedRealm;
    invalidateA1();
    return this;
  }

  /**
   * Returns the quoted value of the {@code realm} directive.
   *
   * @return the quoted value of the {@code realm} directive
   * @see #quotedRealm(String)
   * @see #realm(String)
   * @see #getRealm()
   */
  public String getQuotedRealm() {
    return quotedRealm;
  }

  /**
   * Sets the {@code realm} directive, which must be the same as the {@code realm} directive of
   * the challenge.
   *
   * @param unquotedRealm the unquoted value of the {@code realm} directive
   * @return this object so that setters can be chained
   * @see #getRealm()
   * @see #quotedRealm(String)
   * @see #getQuotedRealm()
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.1">Section 3.2.1 of RFC 2617</a>
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">Section 3.2.2 of RFC 2617</a>
   */
  public DigestChallengeResponse realm(String unquotedRealm) {
    if (unquotedRealm == null) {
      return quotedRealm(null);
    }
    return quotedRealm(Rfc2616AbnfParser.quote(unquotedRealm));
  }

  /**
   * Returns the unquoted value of the {@code realm} directive.
   *
   * @return the unquoted value of the {@code realm} directive
   * @see #realm(String)
   * @see #quotedRealm(String)
   * @see #getQuotedRealm()
   */
  public String getRealm() {
    // TODO: Cache since value is used each time a header is written
    if (quotedRealm == null) {
      return null;
    }
    return Rfc2616AbnfParser.unquote(quotedRealm);
  }

  /**
   * Sets the HTTP method of the request (GET, POST, etc).
   *
   * @param requestMethod the request method
   * @return this object so that setters can be chained
   * @see #getRequestMethod()
   * @see <a href="https://tools.ietf.org/html/rfc2616#section-5.1.1">Section 5.1.1 of RFC 2616</a>
   */
  public DigestChallengeResponse requestMethod(String requestMethod) {
    this.requestMethod = requestMethod;
    return this;
  }

  /**
   * Returns the HTTP method of the request.
   *
   * @return the HTTP method
   * @see #requestMethod(String)
   */
  public String getRequestMethod() {
    return requestMethod;
  }

  /**
   * Sets the values of the {@code realm}, {@code nonce}, {@code opaque}, and {@code algorithm}
   * directives and the supported quality of protection types based on a challenge.
   *
   * @param challenge the challenge
   * @return this object so that setters can be chained
   * @throws UnsupportedAlgorithmHttpDigestException if the challenge uses an algorithm that is
   *                                                 not supported
   */
  public DigestChallengeResponse challenge(DigestChallenge challenge) throws
      UnsupportedAlgorithmHttpDigestException {
    return quotedNonce(challenge.getQuotedNonce()).quotedOpaque(challenge.getQuotedOpaque())
        .quotedRealm(challenge.getQuotedRealm())
        .algorithm(challenge.getAlgorithm())
        .supportedQopTypes(challenge.getSupportedQopTypes());
  }

  /**
   * Returns the {@code credentials}, that is, the string to set in the {@code Authorization}
   * HTTP request header.
   * <p>
   * Before calling this method, the following values and directives must be set:
   * <ul>
   * <li>{@link #username(String) username}.</li>
   * <li>{@link #password(String) password}.</li>
   * <li>{@link #quotedRealm(String) realm}.</li>
   * <li>{@link #quotedNonce(String) nonce}.</li>
   * <li>{@link #digestUri(String) digest-uri}.</li>
   * <li>{@link #requestMethod(String) Method}.</li>
   * </ul>
   *
   * @return the string to set in the {@code Authorization} HTTP request header
   * @throws IllegalStateException if any of the mandatory directives and values listed above has
   *                               not been set
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.2">Section 3.2.2 of RFC 2617</a>
   */
  public String getHeaderValue() {
    QualityOfProtection qop = selectQop();

    if (username == null) {
      throw new IllegalStateException("Mandatory username not set");
    }
    if (password == null) {
      throw new IllegalStateException("Mandatory password not set");
    }
    if (quotedRealm == null) {
      throw new IllegalStateException("Mandatory realm not set");
    }
    if (quotedNonce == null) {
      throw new IllegalStateException("Mandatory nonce not set");
    }
    if (digestUri == null) {
      throw new IllegalStateException("Mandatory digest-uri not set");
    }
    if (requestMethod == null) {
      throw new IllegalStateException("Mandatory Method not set");
    }
    if (clientNonce == null && qop != QualityOfProtection.UNSPECIFIED_RFC2069_COMPATIBLE) {
      throw new IllegalStateException("Client nonce must be set when qop is set");
    }
    if ("MD5-sess".equals(getAlgorithm()) && getFirstRequestClientNonce() == null) {
      throw new IllegalStateException(
          "First request client nonce must be set when algorithm is MD5-sess");
    }

    String response = calculateResponse(qop);

    StringBuilder result = new StringBuilder();
    result.append("Digest ");

    // Username is defined in Section 3.2.2 of RFC 2617
    // username         = "username" "=" username-value
    // username-value   = quoted-string
    result.append("username=");
    result.append(Rfc2616AbnfParser.quote(username));
    result.append(",");

    // Realm is defined in RFC 2617, Section 1.2
    // realm       = "realm" "=" realm-value
    // realm-value = quoted-string
    result.append("realm=");
    result.append(quotedRealm);
    result.append(",");

    // nonce             = "nonce" "=" nonce-value
    // nonce-value       = quoted-string
    result.append("nonce=");
    result.append(quotedNonce);
    result.append(",");

    // digest-uri       = "uri" "=" digest-uri-value
    // digest-uri-value = request-uri   ; As specified by HTTP/1.1
    result.append("uri=");
    result.append(Rfc2616AbnfParser.quote(digestUri));
    result.append(",");

    // Response is defined in RFC 2617, Section 3.2.2 and 3.2.2.1
    // response         = "response" "=" request-digest
    result.append("response=");
    result.append(response);
    result.append(",");

    // cnonce is defined in RFC 2617, Section 3.2.2
    // cnonce           = "cnonce" "=" cnonce-value
    // cnonce-value     = nonce-value
    // Must be present if qop is specified, must not if qop is unspecified
    if (qop != QualityOfProtection.UNSPECIFIED_RFC2069_COMPATIBLE) {
      result.append("cnonce=");
      result.append(Rfc2616AbnfParser.quote(getClientNonce()));
      result.append(",");
    }

    // Opaque and algorithm are explained in Section 3.2.2 of RFC 2617:
    // "The values of the opaque and algorithm fields must be those supplied
    // in the WWW-Authenticate response header for the entity being
    // requested."

    if (quotedOpaque != null) {
      result.append("opaque=");
      result.append(quotedOpaque);
      result.append(",");
    }

    if (algorithm != null) {
      result.append("algorithm=");
      result.append(algorithm);
      result.append(",");
    }

    if (qop != QualityOfProtection.UNSPECIFIED_RFC2069_COMPATIBLE) {
      result.append("qop=");
      result.append(qop.getQopValue());
      result.append(",");
    }

    // Nonce count is defined in RFC 2617, Section 3.2.2
    // nonce-count      = "nc" "=" nc-value
    // nc-value         = 8LHEX (lower case hex)
    // Must be present if qop is specified, must not if qop is unspecified
    if (qop != QualityOfProtection.UNSPECIFIED_RFC2069_COMPATIBLE) {
      result.append("nc=");
      result.append(String.format("%08x", nonceCount));
    }

    return result.toString();
  }

  private QualityOfProtection selectQop() {
    if (supportedQopTypes.contains(QualityOfProtection.AUTH)) {
      return QualityOfProtection.AUTH;
    }

    if (supportedQopTypes.contains(QualityOfProtection.UNSPECIFIED_RFC2069_COMPATIBLE)) {
      return QualityOfProtection.UNSPECIFIED_RFC2069_COMPATIBLE;
    }

    // Note: As a fallback, use the unspecified, RFC2069 qop. The server has specified which qops it
    // supports, but the list does not include any qop we recognize. If the server specified a qop,
    // we SHOULD include a qop directive but it MUST be from the list of specified qops. Given the
    // choice we violate the SHOULD directive insted of the MUST, see the description of qop in
    // Section 3.2.2 of RFC 2617: https://tools.ietf.org/html/rfc2617#section-3.2.2
    return QualityOfProtection.UNSPECIFIED_RFC2069_COMPATIBLE;
  }

  private String calculateResponse(QualityOfProtection qop) {
    String a1 = getA1();
    String a2 = calculateA2();
    String secret = H(a1);
    String data = "";

    switch (qop) {
      case AUTH:
      case AUTH_INT:
        data = joinWithColon(getNonce(),
            String.format("%08x", nonceCount),
            getClientNonce(),
            "auth",
            H(a2));
        break;
      case UNSPECIFIED_RFC2069_COMPATIBLE: {
        data = joinWithColon(getNonce(), H(a2));
        break;
      }
    }

    return "\"" + KD(secret, data) + "\"";
  }

  private String getA1() {
    if (A1 == null) {
      A1 = calculateA1();
    }
    return A1;
  }

  private String calculateA1() {
    if (getAlgorithm() == null || "MD5".equals(getAlgorithm())) {
      return joinWithColon(username, getRealm(), password);
    } else if ("MD5-sess".equals(getAlgorithm())) {
      return joinWithColon(H(joinWithColon(username, getRealm(), password)),
          getNonce(),
          getFirstRequestClientNonce());
    } else {
      throw new RuntimeException("Unsupported algorithm: " + getAlgorithm());
    }
  }

  private void invalidateA1() {
    A1 = null;
  }

  private String calculateA2() {
    // TODO: Below calculation if if qop is auth or unspecified
    // TODO: Support auth-int qop
    return joinWithColon(requestMethod, digestUri);
  }

  private String joinWithColon(String... parts) {
    StringBuilder result = new StringBuilder();

    for (String part : parts) {
      if (result.length() > 0) {
        result.append(":");
      }
      result.append(part);
    }

    return result.toString();
  }

  /**
   * Calculates the function H for some string, as per the description of algorithm in
   * <a href="https://tools.ietf.org/html/rfc2617#section-3.2.1">Section 3.2.1 in RFC 2617.</a>
   * <p>
   * <blockquote>
   * For the "MD5" and "MD5-sess" algorithms
   *
   * H(data) = MD5(data)
   * </blockquote>
   *
   * @param string the string
   * @return the value of <em>H(string)</em>
   */
  private String H(String string) {
    md5.reset();
    // TODO find out which encoding to use
    md5.update(string.getBytes());
    return encodeHexString(md5.digest());
  }

  /**
   * Calculates the function KD for some secret and data, as per the description of algorithm in
   * <a href="https://tools.ietf.org/html/rfc2617#section-3.2.1">Section 3.2.1 in RFC 2617.</a>
   * <p>
   * For MD5:
   * <blockquote>
   * KD(secret, data) = H(concat(secret, ":", data))
   * </blockquote>
   *
   * @param secret the secret
   * @param data   the data
   * @return the value of <em>KD(secret, data)</em>
   */
  private String KD(String secret, String data) {
    return H(secret + ":" + data);
  }

  private static String encodeHexString(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      result.append(Integer.toHexString((b & 0xf0) >> 4));
      result.append(Integer.toHexString((b & 0x0f)));
    }
    return result.toString();
  }

  private static synchronized String generateRandomNonce() {
    RANDOM.nextBytes(clientNonceByteBuffer);
    return encodeHexString(clientNonceByteBuffer);
  }
}
