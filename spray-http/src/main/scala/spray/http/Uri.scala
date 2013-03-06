/*
 * Copyright (C) 2011-2012 spray.io
 * Based on code copyright (C) 2010-2011 by the BlueEyes Web Framework Team (http://github.com/jdegoes/blueeyes)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spray.http

import java.lang.{StringBuilder => JStringBuilder}
import java.nio.charset.Charset
import scala.annotation.tailrec
import spray.http.parser.UriParser
import UriParser._
import Uri._

/**
 * An immutable model of an internet URI as defined by http://tools.ietf.org/html/rfc3986.
 * All members of this class represent the *decoded* URI elements (i.e. without percent-encoding).
 */
abstract case class Uri(scheme: String,
                        authority: Authority,
                        path: Path,
                        query: QueryParams,
                        fragment: Option[String]) {

  def isAbsolute: Boolean = !isRelative
  def isRelative: Boolean = scheme.isEmpty

  def toString(charset: Charset): String = render(charset = charset).toString
  override def toString: String = toString(UTF8)
  def inspect: String = s"Uri($scheme,$authority,$path,$query,$fragment)"

  /**
   * Returns a copy of this Uri with the given components.
   */
  def copy(scheme: String = scheme, authority: Authority = authority, path: Path = path,
           query: QueryParams = Map.empty, fragment: Option[String] = None): Uri =
    Uri(scheme, authority, path, query, fragment)

  /**
   * Returns a new absolute Uri that is the result of the resolution process defined by
   * http://tools.ietf.org/html/rfc3986#section-5.2.2
   * The given base Uri must be absolute.
   */
  def resolvedAgainst(base: Uri): Uri =
    resolve(scheme, authority.userinfo, authority.host, authority.port, path, query, fragment, base)

  /**
   * Renders this Uri into a String as defined by http://tools.ietf.org/html/rfc3986.
   * All Uri components are encoded and joined as required by the spec. The given charset is used to
   * produce percent-encoded representations of potentially existing non-ASCII characters in the
   * different components.
   */
  def render(sb: JStringBuilder = new JStringBuilder, charset: Charset = UTF8): JStringBuilder = {
    class QueryRenderer extends (((String, String)) => Unit) {
      private[this] var first = true
      def apply(kvp: (String, String)): Unit = {
        if (!first) sb.append('&')
        sb.append(enc(kvp._1))
        if (!kvp._2.isEmpty) sb.append('=').append(enc(kvp._2))
        first = false
      }
      def enc(s: String) = encode(s, charset, QUERY_FRAGMENT_CHARS & ~(AMP | EQUAL | PLUS) | SPACE).replace(' ', '+')
    }
    if (isAbsolute) sb.append(scheme).append(':')
    authority.render(sb, scheme, charset)
    path.render(sb, charset, encodeFirstSegmentColons = isRelative)
    if (!query.isEmpty) {
      sb.append('?')
      query.foreach(new QueryRenderer)
    }
    if (fragment.isDefined) sb.append('#').append(encode(fragment.get, charset, QUERY_FRAGMENT_CHARS))
    sb
  }
}

object Uri {
  val Empty = Uri()

  /**
   * Parses a string into a normalized URI reference as defined by http://tools.ietf.org/html/rfc3986#section-4.1.
   * Percent-encoded octets are UTF-8 decoded.
   * If the given string is not a valid URI the method throws an `IllegalUriException`.
   */
  def apply(string: String): Uri = apply(string, UTF8)

  /**
   * Parses a string into a normalized URI reference as defined by http://tools.ietf.org/html/rfc3986#section-4.1.
   * Percent-encoded octets are decoded using the given charset (where specified by the RFC).
   * If the given string is not a valid URI the method throws an `IllegalUriException`.
   */
  def apply(string: String, charset: Charset): Uri =
    new UriParser(string, charset).parseReference()

  /**
   * Creates a new Uri instance from the given components.
   * All components are verified and normalized.
   * If the given combination of components does not constitute a valid URI as defined by
   * http://tools.ietf.org/html/rfc3986 the method throws an `IllegalUriException`.
   */
  def apply(scheme: String = "", authority: Authority = Authority.Empty, path: Path = Path.Empty,
            query: QueryParams = Map.empty, fragment: Option[String] = None): Uri = {
    val p = verifyPath(path, scheme, authority.host)
    new Impl(
      scheme = normalizeScheme(scheme),
      authority = authority.normalizedFor(scheme),
      path = if (scheme.isEmpty) p else collapseDotSegments(p),
      query = query,
      fragment = fragment)
  }

  /**
   * Creates a new Uri instance from the given components.
   * All components are verified and normalized.
   * If the given combination of components does not constitute a valid URI as defined by
   * http://tools.ietf.org/html/rfc3986 the method throws an `IllegalUriException`.
   */
  def from(scheme: String = "", userinfo: String = "", host: String = "", port: Int = 0, path: String = "",
           query: QueryParams = Map.empty, fragment: Option[String] = None): Uri =
    apply(scheme, Authority(Host(host), userinfo, normalizePort(port, scheme)), Path(path), query, fragment)

  /**
   * Parses a string into a normalized absolute URI as defined by http://tools.ietf.org/html/rfc3986#section-4.3.
   * Percent-encoded octets are decoded using the given charset (where specified by the RFC).
   * If the given string is not a valid URI the method throws an `IllegalUriException`.
   */
  def parseAbsolute(string: String, charset: Charset = UTF8): Uri =
    new UriParser(string, charset).parseAbsolute()

  /**
   * Parses a string into a normalized URI reference that is immediately resolved against the given base URI as
   * defined by http://tools.ietf.org/html/rfc3986#section-5.2.
   * Note that the given base Uri must be absolute (i.e. define a scheme).
   * Percent-encoded octets are decoded using the given charset (where specified by the RFC).
   * If the given string is not a valid URI the method throws an `IllegalUriException`.
   */
  def parseAndResolve(string: String, base: Uri, charset: Charset = UTF8): Uri =
    new UriParser(string, charset).parseAndResolveReference(base)

  /**
   * Normalizes the given URI string by performing the following normalizations:
   * - the `scheme` and `host` components are converted to lowercase
   * - a potentially existing `port` component is removed if it matches one of the defined default ports for the scheme
   * - percent-encoded octets are decoded if allowed, otherwise they are converted to uppercase hex notation
   * - `.` and `..` path segments are resolved as far as possible
   *
   * If the given string is not a valid URI the method throws an `IllegalUriException`.
   */
  def normalize(uri: String, charset: Charset = UTF8): String =
    Uri(uri, charset).toString(charset)


  case class Authority(host: Host, userinfo: String = "", port: Int = 0) {
    def isEmpty = host.isEmpty
    def render(sb: JStringBuilder, scheme: String = "", charset: Charset = UTF8): JStringBuilder =
      if (isEmpty) sb else {
        sb.append('/').append('/')
        if (!userinfo.isEmpty) sb.append(encode(userinfo, charset, UNRESERVED | SUB_DELIM | COLON)).append('@')
        host.render(sb)
        if (port != 0) normalizePort(port, scheme) match {
          case 0 => sb
          case x => sb.append(':').append(port)
        } else sb
      }
    def toString(charset: Charset): String = render(new JStringBuilder).toString
    override def toString: String = toString(UTF8)
    def normalizedFor(scheme: String): Authority = {
      val normalizedPort = normalizePort(port, scheme)
      if (normalizedPort == port) this else copy(port = normalizedPort)
    }
  }
  object Authority {
    val Empty = Authority(Host.Empty)
  }

  sealed abstract class Host {
    def address: String
    def isEmpty: Boolean
    def render(sb: JStringBuilder): JStringBuilder
    override def toString: String = render(new JStringBuilder).toString
  }
  object Host {
    case object Empty extends Host {
      def address: String = ""
      override def isEmpty = true
      def render(sb: JStringBuilder) = sb
    }
    def apply(string: String): Host =
      if (!string.isEmpty){
        val parser = new UriParser(string)
        import parser._
        complete("URI host", host)
        _host
      } else Empty
  }
  sealed abstract class NonEmptyHost extends Host {
    def isEmpty = false
  }
  case class IPv4Host(address: String) extends NonEmptyHost {
    require(!address.isEmpty, "address must not be empty")
    def render(sb: JStringBuilder) = sb.append(address)
  }
  case class IPv6Host(address: String) extends NonEmptyHost {
    require(!address.isEmpty, "address must not be empty")
    def render(sb: JStringBuilder) = sb.append('[').append(address).append(']')
  }
  case class NamedHost(address: String) extends NonEmptyHost {
    def render(sb: JStringBuilder) = sb.append(encode(address, UTF8, UNRESERVED | SUB_DELIM))
  }

  sealed abstract class Path {
    type Head
    def isEmpty: Boolean
    def startsWithSlash: Boolean
    def startsWithSegment: Boolean
    def head: Head
    def tail: Path
    def length: Int
    def charCount: Int
    def render(sb: JStringBuilder, charset: Charset = UTF8, encodeFirstSegmentColons: Boolean = false): JStringBuilder
    def ::(c: Char) = Path.Slash(this)
    def ::(segment: String): Path.Segment
    def :::(prefix: Path): Path = prefix ++ this
    def ++(suffix: Path): Path
    def reverse: Path = reverseAndPrependTo(Path.Empty)
    def reverseAndPrependTo(prefix: Path): Path
    def toString(charset: Charset) = render(new JStringBuilder, charset).toString
    override def toString = toString(UTF8)
  }
  object Path {
    val / = Slash(Empty)
    def apply(string: String, charset: Charset = UTF8): Path = {
      @tailrec def build(path: Path = Empty, ix: Int = string.length - 1, segmentEnd: Int = 0): Path = {
        if (ix >= 0)
          if (string.charAt(ix) == '/')
            if (segmentEnd == 0) build(Slash(path), ix - 1)
            else build(Slash(decode(string.substring(ix + 1, segmentEnd), charset) :: path), ix - 1)
          else
            if (segmentEnd == 0) build(path, ix - 1, ix + 1)
            else build(path, ix - 1, segmentEnd)
        else
          if (segmentEnd == 0) path else decode(string.substring(0, segmentEnd), charset) :: path
      }
      build()
    }
    sealed abstract class SlashOrEmpty extends Path {
      def startsWithSegment = false
    }
    case object Empty extends SlashOrEmpty {
      type Head = Nothing
      def isEmpty = true
      def startsWithSlash = false
      def head: Head = throw new NoSuchElementException("head of empty path")
      def tail: Path = throw new UnsupportedOperationException("tail of empty path")
      def length = 0
      def charCount = 0
      def render(sb: JStringBuilder, charset: Charset = UTF8, encodeFirstSegmentColons: Boolean = false) = sb
      def ::(segment: String) = Segment(segment, this)
      def ++(suffix: Path) = suffix
      def reverseAndPrependTo(prefix: Path) = prefix
    }
    case class Slash(tail: Path) extends SlashOrEmpty {
      type Head = Char
      def head = '/'
      def startsWithSlash = true
      def isEmpty = false
      def length: Int = tail.length + 1
      def charCount: Int = tail.charCount + 1
      def render(sb: JStringBuilder, charset: Charset = UTF8, encodeFirstSegmentColons: Boolean = false) =
        tail.render(sb.append('/'), charset)
      def ::(segment: String) = Segment(segment, this)
      def ++(suffix: Path) = Slash(tail ++ suffix)
      def reverseAndPrependTo(prefix: Path) = tail.reverseAndPrependTo(Slash(prefix))
    }
    case class Segment(head: String, tail: SlashOrEmpty) extends Path {
      type Head = String
      def isEmpty = false
      def startsWithSlash = false
      def startsWithSegment = true
      def length: Int = tail.length + 1
      def charCount: Int = head.length + tail.charCount
      def render(sb: JStringBuilder, charset: Charset = UTF8, encodeFirstSegmentColons: Boolean = false) = {
        val keep = if (encodeFirstSegmentColons) PATH_SEGMENT_CHARS & ~COLON else PATH_SEGMENT_CHARS
        tail.render(sb.append(encode(head, charset, keep)), charset)
      }
      def ::(segment: String) = Segment(segment + head, tail)
      def ++(suffix: Path) = head :: (tail ++ suffix)
      def reverseAndPrependTo(prefix: Path): Path = tail.reverseAndPrependTo(head :: prefix)
    }
    object ~ {
      def unapply(cons: Segment): Option[(String, Path)] = Some((cons.head, cons.tail))
      def unapply(cons: Slash): Option[(Char, Path)] = Some(('/', cons.tail))
    }
  }

  val defaultPorts: Map[String, Int] =
    Map("ftp" -> 21, "ssh" -> 22, "telnet" -> 23, "smtp" -> 25, "domain" -> 53, "tftp" -> 69, "http" -> 80,
      "pop3" -> 110, "nntp" -> 119, "imap" -> 143, "snmp" -> 161, "ldap" -> 389, "https" -> 443, "imaps" -> 993,
      "nfs" -> 2049).withDefaultValue(-1)


  /////////////////////////////////// PRIVATE //////////////////////////////////////////

  // http://tools.ietf.org/html/rfc3986#section-5.2.2
  private[http] def resolve(scheme: String, userinfo: String, host: Host, port: Int, path: Path, query: QueryParams,
                            fragment: Option[String], base: Uri): Uri = {
    require(base.isAbsolute, "Resolution base Uri must be absolute")
    if (scheme.isEmpty)
      if (host.isEmpty)
        if (path.isEmpty) {
          val q = if (query.isEmpty) base.query else query
          new Impl(base.scheme, base.authority, base.path, q, fragment)
        } else {
          // http://tools.ietf.org/html/rfc3986#section-5.2.3
          def mergePaths(base: Uri, path: Path): Path =
            if (!base.authority.isEmpty && path.isEmpty) Path.Slash(path)
            else {
              import Path._
              def replaceLastSegment(p: Path, replacement: Path): Path = p match {
                case Path.Empty | Segment(_, Path.Empty) => replacement
                case Segment(string, tail) => string :: replaceLastSegment(tail, replacement)
                case Slash(tail) => Slash(replaceLastSegment(tail, replacement))
              }
              replaceLastSegment(base.path, path)
            }
          val p = if (path.startsWithSlash) path else mergePaths(base, path)
          new Impl(base.scheme, base.authority, collapseDotSegments(p), query, fragment)
        }
      else new Impl(base.scheme, userinfo, host, port, collapseDotSegments(path), query, fragment)
    else new Impl(scheme, userinfo, host, port, collapseDotSegments(path), query, fragment)
  }

  private[http] def encode(string: String, charset: Charset, keep: Int): String = {
    @tailrec def firstToBeEncoded(ix: Int = 0): Int =
      if (ix == string.length) -1 else if (is(string.charAt(ix), keep)) firstToBeEncoded(ix + 1) else ix

    firstToBeEncoded() match {
      case -1 => string
      case first =>
        @tailrec def process(sb: JStringBuilder, ix: Int = first): String = {
          def appendEncoded(byte: Byte): Unit = sb.append('%').append(hexDigit(byte >>> 4)).append(hexDigit(byte))
          if (ix < string.length) {
            string.charAt(ix) match {
              case c if is(c, keep) => sb.append(c)
              case c if c <= 127 => appendEncoded(c.toByte)
              case c => c.toString.getBytes(charset).foreach(appendEncoded)
            }
            process(sb, ix + 1)
          } else sb.toString
        }
        process(new JStringBuilder(string.length * 2).append(string, 0, first))
    }
  }

  private[http] def decode(string: String, charset: Charset): String = {
    val ix = string.indexOf('%')
    if (ix >= 0) decode(string, charset, ix)() else string
  }

  @tailrec
  private[http] def decode(string: String, charset: Charset, ix: Int)
                          (sb: JStringBuilder = new JStringBuilder(string.length).append(string, 0, ix)): String =
    if (ix < string.length) string.charAt(ix) match {
      case '%' =>
        def intValueOfHexWord(i: Int) = {
          def intValueOfHexChar(j: Int) = {
            val c = string.charAt(j)
            if (is(c, DIGIT)) c - '0'
            else if (is(c, HEX_LETTER)) toLowerCase(c) - 'a' + 10
            else throw new IllegalArgumentException("Illegal percent-encoding at pos " + j)
          }
          intValueOfHexChar(i) * 16 + intValueOfHexChar(i + 1)
        }

        var lastPercentSignIndexPlus3 = ix + 3
        while (lastPercentSignIndexPlus3 < string.length && string.charAt(lastPercentSignIndexPlus3) == '%')
          lastPercentSignIndexPlus3 += 3
        val bytesCount = (lastPercentSignIndexPlus3 - ix) / 3
        val bytes = new Array[Byte](bytesCount)

        @tailrec def decodeBytes(i: Int = 0, oredBytes: Int = 0): Int = {
          if (i < bytesCount) {
            val byte = intValueOfHexWord(ix + 3 * i + 1)
            bytes(i) = byte.toByte
            decodeBytes(i + 1, oredBytes | byte)
          } else oredBytes
        }

        if ((decodeBytes() >> 7) != 0) { // if non-ASCII chars are present we need to involve the charset for decoding
          sb.append(new String(bytes, charset))
        } else {
          @tailrec def appendBytes(i: Int = 0): Unit =
            if (i < bytesCount) { sb.append(bytes(i).toChar); appendBytes(i + 1) }
          appendBytes()
        }
        decode(string, charset, lastPercentSignIndexPlus3)(sb)

      case x => decode(string, charset, ix + 1)(sb.append(x))
    } else sb.toString

  private[http] def normalizeScheme(scheme: String): String = {
    @tailrec def verify(ix: Int = scheme.length - 1, allowed: Int = ALPHA, allLower: Boolean = true): Int =
      if (ix >= 0) {
        val c = scheme.charAt(ix)
        if (is(c, allowed)) verify(ix - 1, ALPHA | DIGIT | PLUS | DASH | DOT, allLower && !is(c, UPPER_ALPHA)) else ix
      } else if (allLower) -1 else -2
    verify() match {
      case -2 => scheme.toLowerCase
      case -1 => scheme
      case ix => fail(s"Invalid URI scheme, unexpected character at pos $ix ('${scheme.charAt(ix)}')")
    }
  }

  private[http] def normalizePort(port: Int, scheme: String): Int =
    if ((port >> 16) == 0)
      if (port != 0 && defaultPorts(scheme) == port) 0 else port
    else fail("Invalid port " + port)

  private[http] def verifyPath(path: Path, scheme: String, host: Host): Path = {
    if (host.isEmpty) {
      if (path.startsWithSlash && path.tail.startsWithSlash)
        fail("""The path of an URI without authority must not begin with "//"""")
    } else if (path.startsWithSegment)
      fail("The path of an URI containing an authority must either be empty or start with a '/' (slash) character")
    path
  }

  private[http] def collapseDotSegments(path: Path): Path = {
    @tailrec def hasDotOrDotDotSegment(p: Path): Boolean = p match {
      case Path.Empty => false
      case Path.Segment(".", _) | Path.Segment("..", _) => true
      case _ => hasDotOrDotDotSegment(p.tail)
    }
    // http://tools.ietf.org/html/rfc3986#section-5.2.4
    @tailrec def process(input: Path, output: Path = Path.Empty): Path = {
      import Path._
      input match {
        case Path.Empty => output.reverse
        case Segment("." | "..", Slash(tail)) => process(tail, output)
        case Slash(Segment(".", tail)) => process(if (tail.isEmpty) / else tail, output)
        case Slash(Segment("..", tail)) => process(
          input = if (tail.isEmpty) / else tail,
          output =
            if (output.startsWithSegment)
              if (output.tail.startsWithSlash) output.tail.tail else tail
            else output)
        case Segment("." | "..", tail) => process(tail, output)
        case Slash(tail) => process(tail, Slash(output))
        case Segment(string, tail) => process(tail, string :: output)
      }
    }
    if (hasDotOrDotDotSegment(path)) process(path) else path
  }

  private[http] def fail(msg: String) = throw new IllegalUriException(msg)

  private[http] class Impl(scheme: String, authority: Authority, path: Path, query: QueryParams,
                           fragment: Option[String]) extends Uri(scheme, authority, path, query, fragment) {
    def this(scheme: String, userinfo: String, host: Host, port: Int, path: Path, query: QueryParams,
             fragment: Option[String]) =
      this(scheme, Authority(host, userinfo, normalizePort(port, scheme)), path, query, fragment)
  }
}

class IllegalUriException(msg: String) extends RuntimeException(msg)