package com.planet_ink.emutil.archives;

import java.io.*;
import java.util.*;

import com.planet_ink.emutil.D64Base;

public class ZipCode
{
	private static final int[] DECODE = new int[32];
	static
	{
		for (int i = 0; i < 32; i++)
			DECODE[i] = -1;
		DECODE[9] = 0x8;
		DECODE[10] = 0x0;
		DECODE[11] = 0x1;
		DECODE[13] = 0xC;
		DECODE[14] = 0x4;
		DECODE[15] = 0x5;
		DECODE[18] = 0x2;
		DECODE[19] = 0x3;
		DECODE[21] = 0xF;
		DECODE[22] = 0x6;
		DECODE[23] = 0x7;
		DECODE[25] = 0x9;
		DECODE[26] = 0xA;
		DECODE[27] = 0xB;
		DECODE[29] = 0xD;
		DECODE[30] = 0xE;
	}

	private static final int[][] INTERLEAVES =
	{
		{ 0, 8, 16, 3, 11, 19, 6, 14, 1, 9, 17, 4, 12, 20, 7, 15, 2, 10, 18, 5, 13 }, // Tracks 1-17
		{ 0, 8, 16, 5, 13, 2, 10, 18, 7, 15, 4, 12, 1, 9, 17, 6, 14, 3, 11 }, // Tracks 18-24
		{ 0, 8, 16, 6, 14, 4, 12, 2, 10, 1, 9, 17, 7, 15, 5, 13, 3, 11 }, // Tracks 25-30
		{ 0, 8, 16, 7, 15, 6, 14, 5, 13, 4, 12, 3, 11, 2, 10, 1, 9 } // Tracks 31-40
	};

	private static final long[][] TRACK_OFFSETS =
	{
		{ 0x0003, 0x1BC1, 0x377F, 0x533D, 0x6EFB, 0x8AB9 }, // File 1: Tracks 1-6
		{ 0x0003, 0x1BC1, 0x377F, 0x533D, 0x6EFB, 0x8AB9 }, // File 2: Tracks 7-12
		{ 0x0003, 0x1BC1, 0x377F, 0x533D, 0x6EFB, 0x8AB9 }, // File 3: Tracks 13-18
		{ 0x0003, 0x1935, 0x3267, 0x4B99, 0x64CB, 0x7DFD, 0x972F }, // File 4: Tracks 19-25
		{ 0x0003, 0x17EF, 0x2FDB, 0x47C7, 0x5FB3, 0x779F, 0x8E45 }, // File 5: Tracks 26-32
		{ 0x0003, 0x16A9, 0x2D4F, 0x43F5, 0x5A9B, 0x7141, 0x87E7, 0x9E8D } // File 6: Tracks 33-40
	};

	private static final int[] SECTORS_PER_TRACK = new int[41];
	static
	{
		for (int t = 1; t <= 17; t++)
			SECTORS_PER_TRACK[t] = 21;
		for (int t = 18; t <= 24; t++)
			SECTORS_PER_TRACK[t] = 19;
		for (int t = 25; t <= 30; t++)
			SECTORS_PER_TRACK[t] = 18;
		for (int t = 31; t <= 40; t++)
			SECTORS_PER_TRACK[t] = 17;
	};

	private static final int[] FIRST_TRACK_PER_FILE = { 1, 7, 13, 19, 26, 33, Integer.MAX_VALUE };

	private static final long[]	CUMULATIVE_OFFSETS	= new long[41];	// Precompute byte offsets in D64
	static
	{
		long offset = 0;
		for (int t = 1; t <= 40; t++)
		{
			CUMULATIVE_OFFSETS[t] = offset;
			offset += SECTORS_PER_TRACK[t] * 256L;
		}
	};

	private static final int[][] TRACK_RANGES =
	{
			{ 1, 8 }, // File 1
			{ 9, 16 }, // File 2
			{ 17, 25 }, // File 3
			{ 26, 35 } // File 4
	};

	public static boolean isInteger(final String str)
	{
		try
		{
			Integer.parseInt(str);
			return true;
		}
		catch(final Exception e)
		{
			return false;
		}
	}

	public static byte[] convert6PackToD64(final byte[][] packs, final BitSet parseFlags) throws IOException
	{
		if(packs.length<6)
			throw new IOException ("Not a 6Pack");
		int totalTracks = 35; // Assume 35 tracks; check signature for confirmation
		for (int i = 0; i < 6; i++)
		{
			// Check signature (assuming all files have the same)
			if(((packs[i][0] & 0xFF) != 0xFF || (packs[i][1] & 0xFF) != 0x03)
			&&(!parseFlags.get(D64Base.PF_NOERRORS)))
				throw new IOException("Invalid signature in file " + i);
			final int sigTracks = (packs[i][2] & 0xFF) - 1;
			if (i == 0)
				totalTracks = sigTracks;
			if((sigTracks != totalTracks)
			&&(!parseFlags.get(D64Base.PF_NOERRORS)))
				throw new IOException("Mismatched track count in signatures");
		}
		//if (totalTracks != 35)
		//	throw new IOException("Warning: Only 35-track images supported for D64 output.");

		final byte[] d64 = new byte[174848];
		int d64Offset = 0;

		for (int t = 1; t <= 35; t++)
		{
			int fileIdx = 0;
			while (t >= FIRST_TRACK_PER_FILE[fileIdx + 1])
				fileIdx++;
			final int trackInFile = t - FIRST_TRACK_PER_FILE[fileIdx];
			final byte[] packData = packs[fileIdx];
			final long trackStart = TRACK_OFFSETS[fileIdx][trackInFile];

			final byte[] descriptor = new byte[256];
			System.arraycopy(packData, (int) trackStart, descriptor, 0, 256);
			final int numSectors = descriptor[255] & 0xFF;
			if((numSectors != SECTORS_PER_TRACK[t])
			&&(!parseFlags.get(D64Base.PF_NOERRORS)))
				throw new IOException("Warning: Unexpected sector count on track " + t + ": " + numSectors);

			final int zone = (t <= 17) ? 0 : (t <= 24) ? 1 : (t <= 30) ? 2 : 3;
			final int[] interleave = INTERLEAVES[zone];

			final long dataStart = trackStart + 256;
			for (int idx = 0; idx < numSectors; idx++)
			{
				final long secStart = dataStart + (long) idx * 326;
				final byte[] gcrSector = new byte[326];
				System.arraycopy(packData, (int) secStart, gcrSector, 0, 326);
				// Reorder: stored as overflow(70) + main(256) -> main(256) + overflow(70)
				final byte[] originalGcr = new byte[326];
				final byte[] decoded;
				System.arraycopy(gcrSector, 70, originalGcr, 0, 256);
				System.arraycopy(gcrSector, 0, originalGcr, 256, 70);
				decoded = decodeGcr(originalGcr, 325, parseFlags);
				if(((decoded[0] & 0xFF) != 0x07)&&(!parseFlags.get(D64Base.PF_NOERRORS)))
					throw new IOException("Warning: Invalid data descriptor on track " + t + ", sector index " + idx);
				byte checksum = 0;
				for (int b = 1; b <= 256; b++)
					checksum ^= (byte)(decoded[b] & 0xFF);
				if((checksum != decoded[257])&&(!parseFlags.get(D64Base.PF_NOERRORS)))
					System.err.println("Warning: Checksum error on track " + t + ", sector index " + idx);

				final int sectorNum = interleave[idx];
				final int sectorOffset = d64Offset + sectorNum * 256;
				System.arraycopy(decoded, 1, d64, sectorOffset, 256);
			}

			d64Offset += SECTORS_PER_TRACK[t] * 256;
		}
		return d64;
	}

	private static byte[] decodeGcr(final byte[] gcr, final int len, final BitSet parseFlags)
	{
		if (len % 5 != 0)
			throw new IllegalArgumentException("GCR length must be multiple of 5");
		final byte[] data = new byte[len * 4 / 5];
		int outPos = 0;
		for (int inPos = 0; inPos < len; inPos += 5)
		{
			long bits = 0;
			for (int b = 0; b < 5; b++)
				bits = (bits << 8) | (gcr[inPos + b] & 0xFF);
			for (int k = 0; k < 8; k++)
			{
				final int shift = 35 - k * 5;
				final int code = (int) ((bits >> shift) & 0x1F);
				final int nybble = DECODE[code];
				if (nybble < 0)
				{
					if (outPos > 258)
						break;
					throw new IllegalArgumentException("Invalid GCR code: " + code +"@"+outPos);
				}
				if ((k % 2) == 0)
					data[outPos] = (byte) (nybble << 4);
				else
				{
					data[outPos] |= (byte) nybble;
					outPos++;
				}
			}
		}
		return data;
	}

	public static byte[] convert4PackToD64(final byte[][] pack, final BitSet parseFlags) throws IOException
	{
		if(pack.length<4)
			throw new IOException ("Not a 4Pack");
		final byte[] d64 = new byte[174848]; // Standard 35-track D64 size
		Arrays.fill(d64, (byte) 0); // Optional: initialize to zeros

		for (int i = 0; i < 4; i++)
		{
			final byte[] packData = pack[i];

			int pos = 0;
			if(((packData[pos++] & 0xFF) != (i == 0 ? 0xFE : 0x00) || (packData[pos++] & 0xFF) != (i == 0 ? 0x03 : 0x04))
			&&(!parseFlags.get(D64Base.PF_NOERRORS)))
				throw new IOException("Invalid load address in file " + i);
			if (i == 0)
				pos += 2;
			while (pos < packData.length)
			{
				final int trackByte = packData[pos++] & 0xFF;
				if (trackByte == 0)
					break;
				final int mode = trackByte >> 6;
				final int track = trackByte & 0x3F;
				final int sector = packData[pos++] & 0xFF;
				if((track < TRACK_RANGES[i][0] || track > TRACK_RANGES[i][1])
				&&(!parseFlags.get(D64Base.PF_NOERRORS)))
					throw new IOException("Warning: Track " + track + " out of range for file " + (i + 1));

				final byte[] sectorData = new byte[256];
				switch (mode)
				{
				case 0: // No compression: 256 bytes
					System.arraycopy(packData, pos, sectorData, 0, 256);
					pos += 256;
					break;
				case 1: // Single byte repeated
					final byte repeatByte = packData[pos++];
					Arrays.fill(sectorData, repeatByte);
					break;
				case 2: // RLE compression
					final int rleLength = packData[pos++] & 0xFF;
					final byte repeatChar = packData[pos++];
					final byte[] rleData = new byte[rleLength];
					System.arraycopy(packData, pos, rleData, 0, rleLength);
					pos += rleLength;
					int outPos = 0;
					for (int j = 0; j < rleLength; j++)
					{
						final byte b = rleData[j];
						if (b == repeatChar)
						{
							if (j + 2 >= rleLength)
								throw new IOException("Invalid RLE data in file " + i);
							final int count = rleData[++j] & 0xFF;
							final byte value = rleData[++j];
							for (int k = 0; k < count; k++)
							{
								if (outPos >= 256)
									throw new IOException("Sector overflow in RLE decode");
								sectorData[outPos++] = value;
							}
						}
						else
						{
							if (outPos >= 256)
								throw new IOException("Sector overflow in RLE decode");
							sectorData[outPos++] = b;
						}
					}
					if (outPos != 256)
						throw new IOException("Sector underflow in RLE decode: " + outPos + " bytes");
					break;
				case 3:
					throw new IOException("Invalid compression mode 3 in file " + i);
				default:
					throw new IOException("Unknown mode " + mode);
				}

				// Place in D64
				final long d64Offset = CUMULATIVE_OFFSETS[track] + (long) sector * 256;
				System.arraycopy(sectorData, 0, d64, (int) d64Offset, 256);
			}
		}
		return d64;
	}
}
