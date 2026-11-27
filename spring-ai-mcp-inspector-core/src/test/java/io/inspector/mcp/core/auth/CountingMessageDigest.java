/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.inspector.mcp.core.auth;

import java.security.MessageDigestSpi;
import java.security.Provider;
import java.security.Security;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test {@link Provider} that intercepts {@code SHA-256}
 * {@link java.security.MessageDigest} calls and counts every {@link #engineDigest()}
 * invocation.
 * <p>
 * Uses a pure-Java SHA-256 implementation ({@link SimpleSHA256}) so no SUN delegation or
 * recursion occurs. Designed for a single test thread; static counters are shared across
 * the JVM.
 */
public final class CountingMessageDigest extends Provider {

	private static final String NAME = "Counting";

	private static final AtomicInteger digestCallCount = new AtomicInteger(0);

	private static volatile Provider sunProvider;

	/**
	 * No-arg constructor required by JCA provider contract.
	 */
	public CountingMessageDigest() {
		super(NAME, "1.0", "Counting SHA-256 provider for tests");
		put("MessageDigest.SHA-256", CountingMessageDigestSpi.class.getName());
	}

	/**
	 * Installs this provider by removing the default {@code SUN} provider and inserting
	 * this provider at position 1, so {@code SHA-256} lookups resolve to the counting
	 * implementation.
	 * <p>
	 * The original {@code SUN} provider is captured for later restoration by
	 * {@link #uninstall()}.
	 */
	public static void install() {
		sunProvider = Security.getProvider("SUN");
		if (sunProvider != null) {
			Security.removeProvider("SUN");
		}
		Security.insertProviderAt(new CountingMessageDigest(), 1);
	}

	/**
	 * Restores the original {@code SUN} provider and removes the counting provider. Safe
	 * to call even if {@link #install()} was never called.
	 */
	public static void uninstall() {
		Security.removeProvider(NAME);
		if (sunProvider != null) {
			Security.insertProviderAt(sunProvider, 1);
			sunProvider = null;
		}
	}

	/**
	 * Returns the number of {@link #engineDigest()} calls since the last reset or since
	 * JVM start.
	 * @return the digest call count
	 */
	public static int getDigestCallCount() {
		return digestCallCount.get();
	}

	/**
	 * Resets the digest call counter to zero.
	 */
	public static void resetDigestCallCount() {
		digestCallCount.set(0);
	}

	// ------------------------------------------------------------------ SPI

	/**
	 * SPI implementation that delegates to {@link SimpleSHA256} and counts every
	 * {@link #engineDigest()} call.
	 */
	public static final class CountingMessageDigestSpi extends MessageDigestSpi {

		private final SimpleSHA256 sha256 = new SimpleSHA256();

		@Override
		protected void engineUpdate(final byte input) {
			this.sha256.update(input);
		}

		@Override
		protected void engineUpdate(final byte[] input, final int offset, final int len) {
			this.sha256.update(input, offset, len);
		}

		@Override
		protected byte[] engineDigest() {
			digestCallCount.incrementAndGet();
			return this.sha256.digest();
		}

		@Override
		protected void engineReset() {
			this.sha256.reset();
		}

	}

	// --------------------------------------------------------- pure-Java SHA-256

	/**
	 * Pure-Java SHA-256 implementation per FIPS 180-4.
	 * <p>
	 * Correctness verified against the NIST test vector:
	 * {@code SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad}.
	 */
	static final class SimpleSHA256 {

		private static final int[] K = { 0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1,
				0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe,
				0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa,
				0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
				0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb,
				0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624,
				0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
				0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb,
				0xbef9a3f7, 0xc67178f2 };

		private final byte[] buf = new byte[64];

		private int bufLen;

		private long totalLen;

		private final int[] state = new int[8];

		SimpleSHA256() {
			reset();
		}

		void reset() {
			this.bufLen = 0;
			this.totalLen = 0;
			this.state[0] = 0x6a09e667;
			this.state[1] = 0xbb67ae85;
			this.state[2] = 0x3c6ef372;
			this.state[3] = 0xa54ff53a;
			this.state[4] = 0x510e527f;
			this.state[5] = 0x9b05688c;
			this.state[6] = 0x1f83d9ab;
			this.state[7] = 0x5be0cd19;
		}

		void update(final byte b) {
			this.buf[this.bufLen++] = b;
			this.totalLen++;
			if (this.bufLen == 64) {
				compress(this.buf, 0);
				this.bufLen = 0;
			}
		}

		void update(final byte[] b, final int off, final int len) {
			int pos = off;
			int remaining = len;
			while (remaining > 0) {
				final int space = 64 - this.bufLen;
				final int chunk = Math.min(remaining, space);
				System.arraycopy(b, pos, this.buf, this.bufLen, chunk);
				pos += chunk;
				this.bufLen += chunk;
				this.totalLen += chunk;
				remaining -= chunk;
				if (this.bufLen == 64) {
					compress(this.buf, 0);
					this.bufLen = 0;
				}
			}
		}

		byte[] digest() {
			final long bits = this.totalLen * 8;
			// padding
			this.buf[this.bufLen++] = (byte) 0x80;
			if (this.bufLen > 56) {
				Arrays.fill(this.buf, this.bufLen, 64, (byte) 0);
				compress(this.buf, 0);
				this.bufLen = 0;
			}
			while (this.bufLen < 56) {
				this.buf[this.bufLen++] = (byte) 0;
			}
			// length in bits (big-endian)
			this.buf[56] = (byte) (bits >>> 56);
			this.buf[57] = (byte) (bits >>> 48);
			this.buf[58] = (byte) (bits >>> 40);
			this.buf[59] = (byte) (bits >>> 32);
			this.buf[60] = (byte) (bits >>> 24);
			this.buf[61] = (byte) (bits >>> 16);
			this.buf[62] = (byte) (bits >>> 8);
			this.buf[63] = (byte) bits;
			compress(this.buf, 0);

			final byte[] hash = new byte[32];
			for (int i = 0; i < 8; i++) {
				hash[i * 4] = (byte) (this.state[i] >>> 24);
				hash[i * 4 + 1] = (byte) (this.state[i] >>> 16);
				hash[i * 4 + 2] = (byte) (this.state[i] >>> 8);
				hash[i * 4 + 3] = (byte) this.state[i];
			}
			return hash;
		}

		private void compress(final byte[] block, final int off) {
			final int[] w = new int[64];
			for (int t = 0; t < 16; t++) {
				w[t] = (block[off + t * 4] & 0xff) << 24 | (block[off + t * 4 + 1] & 0xff) << 16
						| (block[off + t * 4 + 2] & 0xff) << 8 | (block[off + t * 4 + 3] & 0xff);
			}
			for (int t = 16; t < 64; t++) {
				final int s0 = (Integer.rotateRight(w[t - 15], 7) ^ Integer.rotateRight(w[t - 15], 18)
						^ (w[t - 15] >>> 3));
				final int s1 = (Integer.rotateRight(w[t - 2], 17) ^ Integer.rotateRight(w[t - 2], 19)
						^ (w[t - 2] >>> 10));
				w[t] = w[t - 16] + s0 + w[t - 7] + s1;
			}

			int a = this.state[0];
			int b = this.state[1];
			int c = this.state[2];
			int d = this.state[3];
			int e = this.state[4];
			int f = this.state[5];
			int g = this.state[6];
			int h = this.state[7];

			for (int t = 0; t < 64; t++) {
				final int S1 = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25);
				final int ch = (e & f) ^ ((~e) & g);
				final int temp1 = h + S1 + ch + K[t] + w[t];
				final int S0 = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22);
				final int maj = (a & b) ^ (a & c) ^ (b & c);
				final int temp2 = S0 + maj;

				h = g;
				g = f;
				f = e;
				e = d + temp1;
				d = c;
				c = b;
				b = a;
				a = temp1 + temp2;
			}

			this.state[0] += a;
			this.state[1] += b;
			this.state[2] += c;
			this.state[3] += d;
			this.state[4] += e;
			this.state[5] += f;
			this.state[6] += g;
			this.state[7] += h;
		}

	}

}
