/*
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.KeySpec;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.util.Base64;
import org.eclipse.jgit.util.Hex;

abstract class WalkEncryption {
	static final WalkEncryption NONE = new NoEncryption();
	static final String JETS3T_CRYPTO_VER = "jets3t-crypto-ver";
	static final String JETS3T_CRYPTO_ALG = "jets3t-crypto-alg";

	abstract OutputStream encrypt(OutputStream output) throws IOException;

	abstract void request(HttpURLConnection conn, String prefix) throws IOException;

	abstract void validate(HttpURLConnection conn, String prefix) throws IOException;

	abstract InputStream decrypt(InputStream input) throws IOException;

	protected void validateImpl(final HttpURLConnection u, final String prefix,
								final String version, final String name) throws IOException {
		String v;

		v = u.getHeaderField(prefix + JETS3T_CRYPTO_VER);
		if(v == null)
			v = "";
		if(!version.equals(v))
			throw new IOException(MessageFormat.format(JGitText.get().unsupportedEncryptionVersion, v));

		v = u.getHeaderField(prefix + JETS3T_CRYPTO_ALG);
		if(v == null)
			v = "";
		if(!name.equalsIgnoreCase(v))
			throw new IOException(MessageFormat.format(JGitText.get().unsupportedEncryptionAlgorithm, v));
	}

	IOException error(Throwable why) {
		return new IOException(MessageFormat
				.format(JGitText.get().encryptionError,
						why.getMessage()), why);
	}

	private static class NoEncryption extends WalkEncryption {
		@Override
		void request(HttpURLConnection u, String prefix) {
		}

		@Override
		void validate(HttpURLConnection u, String prefix)
				throws IOException {
			validateImpl(u, prefix, "", "");
		}

		@Override
		InputStream decrypt(InputStream in) {
			return in;
		}

		@Override
		OutputStream encrypt(OutputStream os) {
			return os;
		}
	}

	static class JetS3tV2 extends WalkEncryption {

		static final String VERSION = "2";
		static final String ALGORITHM = "PBEWithMD5AndDES";
		static final int ITERATIONS = 5000;
		static final int KEY_SIZE = 32;
		static final byte[] SALT = {
				(byte) 0xA4, (byte) 0x0B, (byte) 0xC8, (byte) 0x34,
				(byte) 0xD6, (byte) 0x95, (byte) 0xF3, (byte) 0x13
		};

		static final byte[] ZERO_AES_IV = new byte[16];

		private static final String CRYPTO_VER = VERSION;

		private final String cryptoAlg;

		private final SecretKey secretKey;

		private final AlgorithmParameterSpec paramSpec;

		JetS3tV2(final String algo, final String key)
				throws GeneralSecurityException {
			cryptoAlg = algo;

			Cipher cipher = InsecureCipherFactory.create(cryptoAlg);

			String cryptoName = cryptoAlg.toUpperCase(Locale.ROOT);

			if(!cryptoName.startsWith("PBE"))
				throw new GeneralSecurityException(JGitText.get().encryptionOnlyPBE);

			PBEKeySpec keySpec = new PBEKeySpec(key.toCharArray(), SALT, ITERATIONS, KEY_SIZE);
			secretKey = SecretKeyFactory.getInstance(algo).generateSecret(keySpec);

			boolean useIV = cryptoName.contains("AES");

			if(useIV) {
				IvParameterSpec paramIV = new IvParameterSpec(ZERO_AES_IV);
				paramSpec = new PBEParameterSpec(SALT, ITERATIONS, paramIV);
			} else {
				paramSpec = new PBEParameterSpec(SALT, ITERATIONS);
			}

			cipher.init(Cipher.ENCRYPT_MODE, secretKey, paramSpec);
			cipher.doFinal();
		}

		@Override
		void request(HttpURLConnection u, String prefix) {
			u.setRequestProperty(prefix + JETS3T_CRYPTO_VER, CRYPTO_VER);
			u.setRequestProperty(prefix + JETS3T_CRYPTO_ALG, cryptoAlg);
		}

		@Override
		void validate(HttpURLConnection u, String prefix)
				throws IOException {
			validateImpl(u, prefix, CRYPTO_VER, cryptoAlg);
		}

		@Override
		OutputStream encrypt(OutputStream os) throws IOException {
			try {
				final Cipher cipher = InsecureCipherFactory.create(cryptoAlg);
				cipher.init(Cipher.ENCRYPT_MODE, secretKey, paramSpec);
				return new CipherOutputStream(os, cipher);
			} catch(GeneralSecurityException e) {
				throw error(e);
			}
		}

		@Override
		InputStream decrypt(InputStream in) throws IOException {
			try {
				final Cipher cipher = InsecureCipherFactory.create(cryptoAlg);
				cipher.init(Cipher.DECRYPT_MODE, secretKey, paramSpec);
				return new CipherInputStream(in, cipher);
			} catch(GeneralSecurityException e) {
				throw error(e);
			}
		}
	}

	interface Keys {
		String JGIT_PROFILE = "jgit-crypto-profile";
		String JGIT_VERSION = "jgit-crypto-version";
		String JGIT_CONTEXT = "jgit-crypto-context";
		String X_ALGO = ".algo";
		String X_KEY_ALGO = ".key.algo";
		String X_KEY_SIZE = ".key.size";
		String X_KEY_ITER = ".key.iter";
		String X_KEY_SALT = ".key.salt";
	}

	interface Vals {
		String DEFAULT_VERS = "0";
		String DEFAULT_ALGO = JetS3tV2.ALGORITHM;
		String DEFAULT_KEY_ALGO = JetS3tV2.ALGORITHM;
		String DEFAULT_KEY_SIZE = Integer.toString(JetS3tV2.KEY_SIZE);
		String DEFAULT_KEY_ITER = Integer.toString(JetS3tV2.ITERATIONS);
		String DEFAULT_KEY_SALT = Hex.toHexString(JetS3tV2.SALT);
		String EMPTY = "";
		String REGEX_WS = "\\s+";
		String REGEX_PBE = "(PBE).*(WITH).+(AND).+";
		String REGEX_TRANS = "(.+)/(.+)/(.+)";
	}

	static GeneralSecurityException securityError(String message,
												  Throwable cause) {
		return new GeneralSecurityException(
				MessageFormat.format(JGitText.get().encryptionError, message), cause);
	}

	abstract static class SymmetricEncryption extends WalkEncryption
			implements Keys, Vals {

		final String profile;
		final String version;
		final String cipherAlgo;
		final String paramsAlgo;
		final SecretKey secretKey;

		SymmetricEncryption(Properties props) throws GeneralSecurityException {

			profile = props.getProperty(AmazonS3.Keys.CRYPTO_ALG);
			version = props.getProperty(AmazonS3.Keys.CRYPTO_VER);
			String pass = props.getProperty(AmazonS3.Keys.PASSWORD);

			cipherAlgo = props.getProperty(profile + X_ALGO, DEFAULT_ALGO);

			String keyAlgo = props.getProperty(profile + X_KEY_ALGO, DEFAULT_KEY_ALGO);
			String keySize = props.getProperty(profile + X_KEY_SIZE, DEFAULT_KEY_SIZE);
			String keyIter = props.getProperty(profile + X_KEY_ITER, DEFAULT_KEY_ITER);
			String keySalt = props.getProperty(profile + X_KEY_SALT, DEFAULT_KEY_SALT);

			Cipher cipher = InsecureCipherFactory.create(cipherAlgo);
			SecretKeyFactory factory = SecretKeyFactory.getInstance(keyAlgo);

			final int size;
			try {
				size = Integer.parseInt(keySize);
			} catch(Exception e) {
				throw securityError(X_KEY_SIZE + EMPTY + keySize, e);
			}

			final int iter;
			try {
				iter = Integer.parseInt(keyIter);
			} catch(Exception e) {
				throw securityError(X_KEY_ITER + EMPTY + keyIter, e);
			}

			final byte[] salt;
			try {
				salt = Hex.decode(keySalt.replaceAll(REGEX_WS, EMPTY));
			} catch(Exception e) {
				throw securityError(X_KEY_SALT + EMPTY + keySalt, e);
			}

			KeySpec keySpec = new PBEKeySpec(pass.toCharArray(), salt, iter, size);

			SecretKey keyBase = factory.generateSecret(keySpec);

			String name = cipherAlgo.toUpperCase(Locale.ROOT);
			Matcher matcherPBE = Pattern.compile(REGEX_PBE).matcher(name);
			Matcher matcherTrans = Pattern.compile(REGEX_TRANS).matcher(name);
			if(matcherPBE.matches()) {
				paramsAlgo = cipherAlgo;
				secretKey = keyBase;
			} else if(matcherTrans.find()) {
				paramsAlgo = matcherTrans.group(1);
				secretKey = new SecretKeySpec(keyBase.getEncoded(), paramsAlgo);
			} else {
				throw new GeneralSecurityException(MessageFormat.format(
						JGitText.get().unsupportedEncryptionAlgorithm,
						cipherAlgo));
			}

			cipher.init(Cipher.ENCRYPT_MODE, secretKey);
			cipher.doFinal();

		}

		volatile String context;

		@Override
		OutputStream encrypt(OutputStream output) throws IOException {
			try {
				Cipher cipher = InsecureCipherFactory.create(cipherAlgo);
				cipher.init(Cipher.ENCRYPT_MODE, secretKey);
				AlgorithmParameters params = cipher.getParameters();
				if(params == null) {
					context = EMPTY;
				} else {
					context = Base64.encodeBytes(params.getEncoded());
				}
				return new CipherOutputStream(output, cipher);
			} catch(Exception e) {
				throw error(e);
			}
		}

		@Override
		void request(HttpURLConnection conn, String prefix) {
			conn.setRequestProperty(prefix + JGIT_PROFILE, profile);
			conn.setRequestProperty(prefix + JGIT_VERSION, version);
			conn.setRequestProperty(prefix + JGIT_CONTEXT, context);
		}

		volatile Cipher decryptCipher;

		@Override
		void validate(HttpURLConnection conn, String prefix)
				throws IOException {
			String prof = conn.getHeaderField(prefix + JGIT_PROFILE);
			String vers = conn.getHeaderField(prefix + JGIT_VERSION);
			String cont = conn.getHeaderField(prefix + JGIT_CONTEXT);

			if(prof == null) {
				throw new IOException(MessageFormat
						.format(JGitText.get().encryptionError, JGIT_PROFILE));
			}
			if(vers == null) {
				throw new IOException(MessageFormat
						.format(JGitText.get().encryptionError, JGIT_VERSION));
			}
			if(cont == null) {
				throw new IOException(MessageFormat
						.format(JGitText.get().encryptionError, JGIT_CONTEXT));
			}
			if(!profile.equals(prof)) {
				throw new IOException(MessageFormat.format(
						JGitText.get().unsupportedEncryptionAlgorithm, prof));
			}
			if(!version.equals(vers)) {
				throw new IOException(MessageFormat.format(
						JGitText.get().unsupportedEncryptionVersion, vers));
			}
			try {
				decryptCipher = InsecureCipherFactory.create(cipherAlgo);
				if(cont.isEmpty()) {
					decryptCipher.init(Cipher.DECRYPT_MODE, secretKey);
				} else {
					AlgorithmParameters params = AlgorithmParameters
							.getInstance(paramsAlgo);
					params.init(Base64.decode(cont));
					decryptCipher.init(Cipher.DECRYPT_MODE, secretKey, params);
				}
			} catch(Exception e) {
				throw error(e);
			}
		}

		@Override
		InputStream decrypt(InputStream input) {
			try {
				return new CipherInputStream(input, decryptCipher);
			} finally {
				decryptCipher = null;
			}
		}
	}

	static class JGitV1 extends SymmetricEncryption {

		static final String VERSION = "1";

		static Properties wrap(String algo, String pass) {
			Properties props = new Properties();
			props.put(AmazonS3.Keys.CRYPTO_ALG, algo);
			props.put(AmazonS3.Keys.CRYPTO_VER, VERSION);
			props.put(AmazonS3.Keys.PASSWORD, pass);
			props.put(algo + Keys.X_ALGO, algo);
			props.put(algo + Keys.X_KEY_ALGO, algo);
			props.put(algo + Keys.X_KEY_ITER, DEFAULT_KEY_ITER);
			props.put(algo + Keys.X_KEY_SIZE, DEFAULT_KEY_SIZE);
			props.put(algo + Keys.X_KEY_SALT, DEFAULT_KEY_SALT);
			return props;
		}

		JGitV1(String algo, String pass)
				throws GeneralSecurityException {
			super(wrap(algo, pass));
			String name = cipherAlgo.toUpperCase(Locale.ROOT);
			Matcher matcherPBE = Pattern.compile(REGEX_PBE).matcher(name);
			if(!matcherPBE.matches())
				throw new GeneralSecurityException(
						JGitText.get().encryptionOnlyPBE);
		}

	}

	static class JGitV2 extends SymmetricEncryption {

		static final String VERSION = "2";

		JGitV2(Properties props)
				throws GeneralSecurityException {
			super(props);
		}
	}

	static WalkEncryption instance(Properties props)
			throws GeneralSecurityException {

		String algo = props.getProperty(AmazonS3.Keys.CRYPTO_ALG, Vals.DEFAULT_ALGO);
		String vers = props.getProperty(AmazonS3.Keys.CRYPTO_VER, Vals.DEFAULT_VERS);
		String pass = props.getProperty(AmazonS3.Keys.PASSWORD);

		if(pass == null)
			return WalkEncryption.NONE;

		switch(vers) {
			case Vals.DEFAULT_VERS:
				return new JetS3tV2(algo, pass);
			case JGitV1.VERSION:
				return new JGitV1(algo, pass);
			case JGitV2.VERSION:
				return new JGitV2(props);
			default:
				throw new GeneralSecurityException(MessageFormat.format(
						JGitText.get().unsupportedEncryptionVersion, vers));
		}
	}
}
