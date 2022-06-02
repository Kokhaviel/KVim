//
//  NOTE: The following source code is heavily derived from the
//  iHarder.net public domain Base64 library.  See the original at
//  http://iharder.sourceforge.net/current/java/base64/
//

package org.eclipse.jgit.util;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.text.MessageFormat;
import java.util.Arrays;

import org.eclipse.jgit.internal.JGitText;

public class Base64 {
	private static final byte EQUALS_SIGN = (byte) '=';
	private static final byte EQUALS_SIGN_DEC = -1;
	private static final byte WHITE_SPACE_DEC = -2;
	private static final byte INVALID_DEC = -3;
	private static final byte[] ENC;

	private static final byte[] DEC;

	static {
		ENC = ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/").getBytes(UTF_8);

		DEC = new byte[128];
		Arrays.fill(DEC, INVALID_DEC);

		for(int i = 0; i < 64; i++)
			DEC[ENC[i]] = (byte) i;
		DEC[EQUALS_SIGN] = EQUALS_SIGN_DEC;

		DEC['\t'] = WHITE_SPACE_DEC;
		DEC['\n'] = WHITE_SPACE_DEC;
		DEC['\r'] = WHITE_SPACE_DEC;
		DEC[' '] = WHITE_SPACE_DEC;
	}

	private Base64() {
	}

	private static void encode3to4(byte[] source, int srcOffset,
								   int numSigBytes, byte[] destination, int destOffset) {
		int inBuff = 0;
		switch(numSigBytes) {
			case 3:
				inBuff |= (source[srcOffset + 2] << 24) >>> 24;

			case 2:
				inBuff |= (source[srcOffset + 1] << 24) >>> 16;

			case 1:
				inBuff |= (source[srcOffset] << 24) >>> 8;
		}

		switch(numSigBytes) {
			case 3:
				destination[destOffset] = ENC[(inBuff >>> 18)];
				destination[destOffset + 1] = ENC[(inBuff >>> 12) & 0x3f];
				destination[destOffset + 2] = ENC[(inBuff >>> 6) & 0x3f];
				destination[destOffset + 3] = ENC[(inBuff) & 0x3f];
				break;

			case 2:
				destination[destOffset] = ENC[(inBuff >>> 18)];
				destination[destOffset + 1] = ENC[(inBuff >>> 12) & 0x3f];
				destination[destOffset + 2] = ENC[(inBuff >>> 6) & 0x3f];
				destination[destOffset + 3] = EQUALS_SIGN;
				break;

			case 1:
				destination[destOffset] = ENC[(inBuff >>> 18)];
				destination[destOffset + 1] = ENC[(inBuff >>> 12) & 0x3f];
				destination[destOffset + 2] = EQUALS_SIGN;
				destination[destOffset + 3] = EQUALS_SIGN;
				break;
		}
	}

	public static String encodeBytes(byte[] source) {
		return encodeBytes(source, 0, source.length);
	}

	public static String encodeBytes(byte[] source, int off, int len) {
		final int len43 = len * 4 / 3;

		byte[] outBuff = new byte[len43 + ((len % 3) > 0 ? 4 : 0)];
		int d = 0;
		int e = 0;
		int len2 = len - 2;

		for(; d < len2; d += 3, e += 4)
			encode3to4(source, d + off, 3, outBuff, e);

		if(d < len) {
			encode3to4(source, d + off, len - d, outBuff, e);
			e += 4;
		}

		return new String(outBuff, 0, e, UTF_8);
	}

	private static int decode4to3(byte[] source,
								  byte[] destination, int destOffset) {
		if(source[2] == EQUALS_SIGN) {
			int outBuff = ((DEC[source[0]] & 0xFF) << 18)
					| ((DEC[source[1]] & 0xFF) << 12);
			destination[destOffset] = (byte) (outBuff >>> 16);
			return 1;
		} else if(source[3] == EQUALS_SIGN) {
			int outBuff = ((DEC[source[0]] & 0xFF) << 18)
					| ((DEC[source[1]] & 0xFF) << 12)
					| ((DEC[source[2]] & 0xFF) << 6);
			destination[destOffset] = (byte) (outBuff >>> 16);
			destination[destOffset + 1] = (byte) (outBuff >>> 8);
			return 2;
		} else {
			int outBuff = ((DEC[source[0]] & 0xFF) << 18)
					| ((DEC[source[1]] & 0xFF) << 12)
					| ((DEC[source[2]] & 0xFF) << 6)
					| ((DEC[source[3]] & 0xFF));

			destination[destOffset] = (byte) (outBuff >> 16);
			destination[destOffset + 1] = (byte) (outBuff >> 8);
			destination[destOffset + 2] = (byte) (outBuff);

			return 3;
		}
	}

	public static byte[] decode(byte[] source, int off, int len) {
		byte[] outBuff = new byte[len * 3 / 4];
		int outBuffPosn = 0;

		byte[] b4 = new byte[4];
		int b4Posn = 0;

		for(int i = off; i < off + len; i++) {
			byte sbiCrop = (byte) (source[i] & 0x7f);
			byte sbiDecode = DEC[sbiCrop];

			if(EQUALS_SIGN_DEC <= sbiDecode) {
				b4[b4Posn++] = sbiCrop;
				if(b4Posn > 3) {
					outBuffPosn += decode4to3(b4, outBuff, outBuffPosn);
					b4Posn = 0;

					if(sbiCrop == EQUALS_SIGN)
						break;
				}

			} else if(sbiDecode != WHITE_SPACE_DEC)
				throw new IllegalArgumentException(MessageFormat.format(
						JGitText.get().badBase64InputCharacterAt,
						i, source[i] & 0xff));
		}

		if(outBuff.length == outBuffPosn)
			return outBuff;

		byte[] out = new byte[outBuffPosn];
		System.arraycopy(outBuff, 0, out, 0, outBuffPosn);
		return out;
	}

	public static byte[] decode(String s) {
		byte[] bytes = s.getBytes(UTF_8);
		return decode(bytes, 0, bytes.length);
	}
}
