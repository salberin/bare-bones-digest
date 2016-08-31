package org.barebonesdigest;

/**
 * Represents a HTTP digest challenge, as sent from the server to the client in a {@code
 * WWW-Authenticate} HTTP header.
 *
 * <h2>Parsing the {@code WWW-Authenticate} header</h2>
 *
 * When the server receives a request that requires authorization it can respond with response
 * code 401 and include a {@code WWW-Authenticate} response header. The header includes one or more
 * challenges, some of which may be HTTP Digest challenges.
 * <p>
 * To parse a HTTP Digest challenge pass it to the {@link #parse(String)} method. Note that the
 * HTTP Digest challenge must first be extracted from the header value if the header contains
 * multiple challenges.
 *
 * <h2>Quoted and unquoted values</h2>
 *
 * Where applicable there are two getters for each directive, one for the quoted value (exactly
 * as it appears in the challenge), and one for the unquoted value (with escape sequences parsed
 * and surrounding quotes removed). Quoting is defined in
 * <a href="https://tools.ietf.org/html/rfc2616#section-2.2"> RFC 2616, Section 2.2</a>.
 *
 * <h2>Limitations</h2>
 *
 * <ul>
 * <li>The values of the {@code qop} and {@code realm} directives are parsed but not stored.</li>
 * <li>Non-standard directives are not ignored as the spec dictates. Parsing fails on
 * unrecognized parameters.</li>
 * </ul>
 *
 * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.1">RFC 2617, Section 3.2.1, The
 * WWW-Authenticate Response Header</a>
 * @see <a href="https://tools.ietf.org/html/rfc2616#section-14.47">RFC 7235, Section 14.47,
 * WWW-Authenticate</a>
 */
public class DigestChallenge {
  /**
   * Name of the HTTP response header containing the challenge.
   *
   * @see <a href="https://tools.ietf.org/html/rfc2616#section-14.47">RFC 7235, Section 14.47,
   * WWW-Authenticate</a>
   */
  public static final String HTTP_HEADER_WWW_AUTHENTICATE = "WWW-Authenticate";

  private static final String HTTP_DIGEST_CHALLENGE_PREFIX = "digest";

  private final String quotedRealm;
  private final String quotedNonce;
  private final String quotedOpaque;
  private final String algorithm;
  private final boolean stale;

  private DigestChallenge(String realm,
      String nonce,
      String quotedOpaque,
      String algorithm,
      boolean stale) {
    this.quotedRealm = realm;
    this.quotedNonce = nonce;
    this.quotedOpaque = quotedOpaque;
    this.algorithm = algorithm;
    this.stale = stale;
  }

  /**
   * Parses a HTTP Digest challenge.
   *
   * @param challengeString the challenge as a string
   * @return the parsed challenge, or {@code null} if the string does not contain a challenge or it
   * could not be parsed
   * @see <a href="https://tools.ietf.org/html/rfc2617#section-3.2.1">RFC 2617, Section 3.2.1, The
   * WWW-Authenticate Response Header</a>
   */
  public static DigestChallenge parse(String challengeString) {
    // see https://tools.ietf.org/html/rfc7235#section-4.1
    Rfc2616AbnfParser parser = new Rfc2616AbnfParser(challengeString);
    try {
      parser.consumeLiteral(HTTP_DIGEST_CHALLENGE_PREFIX);
      parser.consumeWhitespace();

      String quotedRealm = null;
      String quotedNonce = null;
      String quotedOpaque = null;
      String algorithm = "MD5";
      boolean stale = false;

      while (parser.containsMoreData()) {
        String token = parser.consumeToken().get();
        parser.consumeWhitespace().consumeLiteral("=").consumeWhitespace();

        switch (token) {
          case "realm":
            // Realm definition from RFC 2617, Section 1.2:
            // realm       = "realm" "=" realm-value
            // realm-value = quoted-string
            quotedRealm = parser.consumeQuotedString().get();
            break;

          case "nonce":
            // Nonce definition from RFC 2617, Section 3.2.1:
            // nonce             = "nonce" "=" nonce-value
            // nonce-value       = quoted-string
            quotedNonce = parser.consumeQuotedString().get();
            break;

          case "opaque":
            // Opaque definition from RFC 2617, Section 3.2.1:
            // opaque            = "opaque" "=" quoted-string
            quotedOpaque = parser.consumeQuotedString().get();
            break;

          case "algorithm":
            // Algorithm definition from RFC 2617, Section 3.2.1:
            // algorithm         = "algorithm" "=" ( "MD5" | "MD5-sess" |
            //                     token )
            algorithm = parser.consumeToken().get();
            break;

          case "qop":
            // Qop definition from RFC 2617, Section 3.2.1:
            // qop-options       = "qop" "=" <"> 1#qop-value <">
            // qop-value         = "auth" | "auth-int" | token
            // TODO: deal with malformed qop
            // TODO store qop
            parser.consumeQuotedString();
            break;

          case "domain":
            // Domain definition from RFC 2617, Section 3.2.1:
            // domain            = "domain" "=" <"> URI ( 1*SP URI ) <">
            // TODO store domain
            parser.consumeQuotedString().get();
            break;

          case "stale":
            // Stale definition from RFC 2617, Section 3.2.1:
            // stale             = "stale" "=" ( "true" | "false" )
            String staleToken = parser.consumeToken().get();
            // TRUE (case-insensitive) means stale, any other value (or stale
            // directive not present) means false. From RFC 2617, Section 3.2.1:
            // [...] If stale is TRUE (case-insensitive), the client may wish to simply retry the
            // request with a new encrypted response [...] If stale is FALSE, or anything other
            // than TRUE, or the stale directive is not present, the username and/or password are
            // invalid [...]
            stale = staleToken.equalsIgnoreCase("true");
            break;

          default:
            // Any other directive can be included (and MUST be ignored).
            // Definition of auth-param from RFC 2617, Section 1.2;
            // auth-param     = token "=" ( token | quoted-string )
            // TODO: Ignore unrecognized auth-params
            throw new Rfc2616AbnfParser.ParseException("Unrecognized auth-param: " + token, parser);
        }

        parser.consumeWhitespace();
        if (parser.containsMoreData()) {
          parser.consumeLiteral(",").consumeWhitespace();
        }
      }

      if (quotedRealm == null) {
        throw new Rfc2616AbnfParser.ParseException("Missing directive: realm");
      }
      if (quotedNonce == null) {
        throw new Rfc2616AbnfParser.ParseException("Missing directive: nonce");
      }

      return new DigestChallenge(quotedRealm, quotedNonce, quotedOpaque, algorithm, stale);
    } catch (Rfc2616AbnfParser.ParseException e) {
      return null;
    }
  }

  /**
   * Returns the quoted version of the mandatory <em>realm</em> directive, exactly as it appears
   * in the challenge.
   *
   * The realm directive is described in
   * <a href="https://tools.ietf.org/html/rfc2617#section-3.2.1">Section 3.2.1 of RFC 2617</a>:
   *
   * <dl>
   * <dt>realm</dt>
   * <dd>A string to be displayed to users so they know which username and password to use.  This
   * string should contain at least the name of the host performing the authentication and might
   * additionally indicate the collection of users who might have access. An example might be
   * "registered_users@gotham.news.com".</dd>
   * </dl>
   *
   * @return The quoted value of the realm directive, exactly as it appears in the challenge
   */
  public String getQuotedRealm() {
    return quotedRealm;
  }

  /**
   * Returns the unquoted version of the mandatory <em>realm</em> directive.
   *
   * The realm directive is described in
   * <a href="https://tools.ietf.org/html/rfc2617#section-3.2.1">Section 3.2.1 of RFC 2617</a>:
   *
   * <dl>
   * <dt>realm</dt>
   * <dd>A string to be displayed to users so they know which username and password to use. This
   * string should contain at least the name of the host performing the authentication and might
   * additionally indicate the collection of users who might have access. An example might be
   * "registered_users@gotham.news.com".</dd>
   * </dl>
   *
   * @return The unquoted value of the realm directive
   */
  public String getRealm() {
    return Rfc2616AbnfParser.unquote(quotedRealm);
  }

  /**
   * Returns the value of the <em>algorithm</em> directive if present, or its default value "MD5" if
   * not present.
   *
   * The algorithm directive is described in
   * <a href="https://tools.ietf.org/html/rfc2617#section-3.2.1">Section 3.2.1 of RFC 2617</a>:
   *
   * <dl>
   * <dt>algorithm</dt>
   *
   * <dd>A string indicating a pair of algorithms used to produce the digest and a checksum. If
   * this is not present it is assumed to be "MD5".  If the algorithm is not understood, the
   * challenge should be ignored (and a different one used, if there is more than one). [&hellip;
   * ]</dd>
   * </dl>
   *
   * @return The value of the algorithm directive or the default value "MD5" if the algorithm
   * directive is not present in the header
   */
  public String getAlgorithm() {
    return algorithm;
  }

  /**
   * Returns the quoted version of the mandatory <em>nonce</em> directive, exactly as it appears
   * in the challenge.
   *
   * The nonce directive is described in
   * <a href="https://tools.ietf.org/html/rfc2617#section-3.2.1">Section 3.2.1 of RFC 2617</a>:
   *
   * <dl>
   * <dt>nonce</dt>
   *
   * <dd>A server-specified data string which should be uniquely generated
   * each time a 401 response is made. It is recommended that this
   * string be base64 or hexadecimal data. Specifically, since the
   * string is passed in the header lines as a quoted string, the
   * double-quote character is not allowed.
   * <p>
   * The contents of the nonce are implementation dependent. The quality
   * of the implementation depends on a good choice. [&hellip;]
   * <p>
   * The nonce is opaque to the client.</dd>
   * </dl>
   *
   * @return The quoted value of the nonce directive, exactly as it appears in the challenge
   */
  public String getQuotedNonce() {
    return quotedNonce;
  }

  /**
   * Returns the unquoted version of the mandatory <em>nonce</em> directive.
   *
   * The nonce directive is described in
   * <a href="https://tools.ietf.org/html/rfc2617#section-3.2.1">Section 3.2.1 of RFC 2617</a>:
   *
   * <dl>
   * <dt>nonce</dt>
   *
   * <dd>A server-specified data string which should be uniquely generated
   * each time a 401 response is made. It is recommended that this
   * string be base64 or hexadecimal data. Specifically, since the
   * string is passed in the header lines as a quoted string, the
   * double-quote character is not allowed.
   * <p>
   * The contents of the nonce are implementation dependent. The quality
   * of the implementation depends on a good choice. [&hellip;]
   * <p>
   * The nonce is opaque to the client.</dd>
   * </dl>
   *
   * @return the unquoted value of the nonce directive
   */
  public String getNonce() {
    return Rfc2616AbnfParser.unquote(quotedNonce);
  }

  /**
   * Returns the quoted version of the <em>opaque</em> directive exactly as it appears in the
   * challenge, or {@code null} if the directive is not present.
   *
   * The opaque directive is described in
   * <a href="https://tools.ietf.org/html/rfc2617#section-3.2.1">Section 3.2.1 of RFC 2617</a>:
   *
   * <dl>
   * <dt>opaque</dt>
   * <dd>A string of data, specified by the server, which should be returned by the client
   * unchanged in the Authorization header of subsequent requests with URIs in the same
   * protection space. It is recommended that this string be base64 or hexadecimal data.</dd>
   * </dl>
   *
   * @return the quoted value of the opaque directive or {@code null} if the
   * opaque directive is not present in the header
   */
  public String getQuotedOpaque() {
    return quotedOpaque;
  }

  /**
   * Returns the unquoted version of the <em>opaque</em> directive, or {@code null} if the directive
   * is not present.
   *
   * The opaque directive is described in
   * <a href="https://tools.ietf.org/html/rfc2617#section-3.2.1">Section 3.2.1 of RFC 2617</a>:
   *
   * <dl>
   * <dt>opaque</dt>
   * <dd>A string of data, specified by the server, which should be returned by the client
   * unchanged in the Authorization header of subsequent requests with URIs in the same
   * protection space. It is recommended that this string be base64 or hexadecimal data.</dd>
   * </dl>
   *
   * @return the quoted value of the opaque directive or {@code null} if the
   * opaque directive is not present in the header
   */
  public String getOpaque() {
    return Rfc2616AbnfParser.unquote(quotedOpaque);
  }

  /**
   * Returns a boolean representation of the <em>stale</em> directive.
   *
   * This method returns {@code true} if the header has a stale directive that is equal to "true"
   * (case-insensitive). If there is no stale directive or it has any other value, {@code false}
   * is returned.
   *
   * The stale directive is described in
   * <a href="https://tools.ietf.org/html/rfc2617#section-3.2.1">Section 3.2.1 of RFC 2617</a>:
   *
   * <dl>
   * <dt>stale</dt>
   * <dd>A flag, indicating that the previous request from the client was rejected because the
   * nonce value was stale. If stale is TRUE (case-insensitive), the client may wish to simply
   * retry the request with a new encrypted response, without reprompting the user for a new
   * username and password. The server should only set stale to TRUE if it receives a request for
   * which the nonce is invalid but with a valid digest for that nonce (indicating that the
   * client knows the correct username/password). If stale is FALSE, or anything other than TRUE,
   * or the stale directive is not present, the username and/or password are invalid, and new
   * values must be obtained.</dd>
   * </dl>
   *
   * @return {@code true} if a stale directive is present in the header and equal to "true"
   * (case-insensitive), {@code false} otherwise.
   */
  public boolean isStale() {
    return stale;
  }
}
