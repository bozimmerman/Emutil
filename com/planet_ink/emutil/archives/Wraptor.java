package com.planet_ink.emutil.archives;

import java.io.*;
import java.util.*;

import com.planet_ink.emutil.CBMDiskImage.FileInfo;
import com.planet_ink.emutil.CBMDiskImage.FileType;

/**
 * Decompressor for Wraptor (WRA/WR3) C64 archive format. Based on LZSS variant
 * with dynamic window size. Supports PRG, SEQ, USR, GEOS file types (GEOS as
 * raw data only).
 */
public class Wraptor
{
	static class Decompressor
	{
		private final byte[]	data;
		private int				pos;
		private int				status;
		private int				bitBuf;
		private int				bitCount;

		private final byte[]	buffer		= new byte[32768];	// 32KB sliding window/output buffer
		private int				buffPointer;
		private int				readBitSize	= 8;				// Starts at 8 bits for offsets

		private int crc = 0x0000; // CRC-16-CCITT initial value

		Decompressor(final byte[] data)
		{
			this.data = data;
			this.pos = 0;
			this.bitBuf = 0;
			this.bitCount = 0;
			this.buffPointer = 0;
			this.status = 0;
		}

		private int getBits(final int numBits) throws IOException
		{
			int value = 0;
			for (int i = 0; i < numBits; i++)
			{
				if (bitCount == 0)
				{
					bitBuf = getByte() & 0xFF;
					bitCount = 8;
				}
				value = (value << 1) | ((bitBuf >> (bitCount - 1)) & 1);
				bitCount--;
			}
			return value;
		}

		private byte getByte() throws IOException
		{
			if (status == -1 || pos >= data.length)
			{
				status = -1;
				return 0;
			}
			return data[pos++];
		}

		// CRC-16-CCITT update (poly 0x1021, no reflect, no final XOR)
		private void updateCrc(final byte b)
		{
			final int val = b & 0xFF;
			crc ^= (val << 8);
			for (int i = 0; i < 8; i++)
			{
				if ((crc & 0x8000) != 0)
				{
					crc = (crc << 1) ^ 0x1021;
				} else
				{
					crc <<= 1;
				}
			}
			crc &= 0xFFFF;
		}

		private List<FileInfo> getContents() throws IOException
		{
			final List<FileInfo> list = new ArrayList<>();
			while (pos + 4 <= data.length)
			{
				// Check signature
				if ((data[pos] & 0xFF) != 0xFF || (data[pos + 1] & 0xFF) != 0x42 || (data[pos + 2] & 0xFF) != 0x4C
						|| (data[pos + 3] & 0xFF) != 0xFF)
				{
					break; // No more entries
				}
				pos += 4;

				// Read null-terminated filename
				final ByteArrayOutputStream nameBaos = new ByteArrayOutputStream();
				byte b;
				while ((b = getByte()) != 0)
				{
					nameBaos.write(b);
				}
				final String fileName = nameBaos.toString("US-ASCII");

				// File type
				final int typeByte = getByte() & 0xFF;
				FileType fileType;
				switch (typeByte)
				{
				case 0x01:
					fileType = FileType.SEQ;
					break;
				case 0x02:
					fileType = FileType.PRG;
					break;
				case 0x03:
					fileType = FileType.USR;
					break;
				case 0x04:
					fileType = FileType.REL;
					break; // Use REL as placeholder for GEOS
				default:
					throw new IOException("Unknown file type: " + typeByte);
				}

				// Reset decompressor state for this entry
				readBitSize = 8;
				buffPointer = 0;
				crc = 0x0000;
				bitBuf = 0;
				bitCount = 0;

				final ByteArrayOutputStream baos = new ByteArrayOutputStream();
				while (true)
				{
					final int type = getBits(1);
					if (type == 0)
					{ // Literal
						final byte dataByte = (byte) getBits(8);
						buffer[buffPointer] = dataByte;
						updateCrc(dataByte);
						buffPointer++;
						if (buffPointer >= 32768)
						{
							baos.write(buffer, 0, buffPointer);
							buffPointer = 0;
						}
					} else
					{ // Backreference
						final int dictOffset = getBits(readBitSize);
						if (dictOffset == 0)
						{
							final int tmp = getBits(1);
							if (tmp == 0)
							{ // EOF
								if (buffPointer > 0)
								{
									baos.write(buffer, 0, buffPointer);
									buffPointer = 0;
								}
								break;
							} else
							{ // Increase bit size
								readBitSize++;
								if (readBitSize > 15)
								{ // Safety limit for 32K window
									throw new IOException("Invalid bit size increase");
								}
							}
						} else
						{
							final int repLength = getBits(5);
							for (int i = 0; i < repLength; i++)
							{ // Note: length as-is (0-31 bytes)
								if (dictOffset - 1 + i >= buffPointer)
								{
									throw new IOException("Invalid dictionary offset");
								}
								final byte copied = buffer[dictOffset - 1 + i];
								buffer[buffPointer] = copied;
								updateCrc(copied);
								buffPointer++;
								if (buffPointer >= 32768)
								{
									baos.write(buffer, 0, buffPointer);
									buffPointer = 0;
								}
							}
						}
					}
				}

				// Read stored CRC (2 bytes, big-endian)
				final int storedCrc = ((getByte() & 0xFF) << 8) | (getByte() & 0xFF);

				// Verify CRC
				if (crc != storedCrc)
				{
					throw new IOException("CRC mismatch for " + fileName + ": calculated 0x" + Integer.toHexString(crc)
							+ ", stored 0x" + Integer.toHexString(storedCrc));
				}

				final byte[] fileData = baos.toByteArray();
				final FileInfo file = new FileInfo();
				file.fileName = fileName;
				file.rawFileName = fileName.getBytes("US-ASCII");
				file.fileType = fileType;
				file.size = fileData.length;
				file.feblocks = (fileData.length + 253) / 254; // Estimated blocks
				file.data = fileData;
				list.add(file);

				// Continue to next entry (pos already advanced)
			}
			return list;
		}
	}

	public static List<FileInfo> getWraptorDeepContents(final byte[] data) throws IOException
	{
		final Decompressor decompressor = new Decompressor(data);
		return decompressor.getContents();
	}
}