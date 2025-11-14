package com.planet_ink.emutil.archives;

import java.io.*;
import java.util.*;

import com.planet_ink.emutil.CBMDiskImage.FileInfo;
import com.planet_ink.emutil.CBMDiskImage.FileType;
/**
 * Adapted from code by Chris Smeets and Marko Makala.
 * You are heroes among men, fellas. Keep the faith.
 */
public class Arc
{
	static class Entry
	{
		byte	version;
		byte	mode;
		int		check;
		long	size;
		int		blocks;
		byte	type;
		byte	fnlen;
		byte[]	name	= new byte[17];
		byte	rl;
		int		date;
	}

	static class Lz
	{
		int		prefix;
		byte	ext;
	}

	static class Decompressor
	{
		private int				byteCount	= 0;
		private final byte[]	data;
		private int				pos;
		private int				status;
		private int				bitBuf;
		private long			crc		= 0;
		private int				crc2	= 0;
		private final long[]	hc		= new long[256];
		private final byte[]	hl		= new byte[256];
		private final byte[]	hv		= new byte[256];
		private int				hcount;
		private int				ctrl	= 254;
		private final Entry		entry	= new Entry();
		private final Lz[]		lztab	= new Lz[4096];
		private final byte[]	stack	= new byte[512];
		private int				state	= 0;
		private int				lzstack	= 0;
		private int				cdlen;
		private int				code;
		private int				wtcl;
		private int				wttcl;
		private int				entryStart;
		private int				lzOldcode	= 0;
		private int				lzIncode	= 0;
		private byte			lzKay		= 0;
		private byte			lzFinchar	= 0;
		private int				lzNcodes	= 0;

		Decompressor(final byte[] data)
		{
			this.data = data;
			this.pos = 0;
			for (int i = 0; i < lztab.length; i++)
			{
				lztab[i] = new Lz();
			}
		}

		private byte getByte()
		{
			if (status == -1)
				return 0;
			if (pos >= data.length)
			{
				status = -1;
				return 0;
			}
			status = 0;
			return data[pos++];
		}

		private int getWord() throws IOException
		{
			final int low = getByte() & 0xFF;
			final int high = getByte() & 0xFF;
			return low | (high << 8);
		}

		private long getThree() throws IOException
		{
			final long low = getByte() & 0xFF;
			final long med = getByte() & 0xFF;
			final long high = getByte() & 0xFF;
			return low | (med << 8) | (high << 16);
		}

		private int getBit() throws IOException
		{
			final int result = bitBuf >>>= 1;
			if (result == 1)
				return 1 & (bitBuf = (getByte() & 0xFF) | 0x0100);
			return 1 & result;
		}

		private void ssort()
		{
			int m = hl.length;
			while ((m >>>= 1) > 0)
			{
				final int k = hl.length - m;
				int j = 1;
				do
				{
					int i = j;
					do
					{
						final int h = i + m;
						if (hl[h - 1] > hl[i - 1])
						{
							final long t = hc[i - 1];
							hc[i - 1] = hc[h - 1];
							hc[h - 1] = t;
							byte u = hv[i - 1];
							hv[i - 1] = hv[h - 1];
							hv[h - 1] = u;
							u = hl[i - 1];
							hl[i - 1] = hl[h - 1];
							hl[h - 1] = u;
							i -= m;
						}
						else
							break;
					} while (i >= 1);
					j++;
				} while (j <= k);
			}
		}

		private byte huffin() throws IOException
		{
			long hcode = 0;
			long mask = 1;
			int size = 1;
			int now = hcount;

			do
			{
				if (getBit() == 1)
					hcode |= mask;
				while (hl[now] == size)
				{
					if (hc[now] == hcode)
						return hv[now];
					if (--now < 0)
					{
						status = -1;
						return 0;
					}
				}
				size++;
				mask <<= 1;
			} while (size < 24);
			status = -1;
			return 0;
		}

		private byte unc() throws IOException
		{
			while (true)
			{
				switch (state)
				{
				case 0:
					lzstack = 0;
					lzNcodes = 258;
					wtcl = 256;
					wttcl = 254;
					cdlen = 9;
					lzOldcode = getCode();
					if (lzOldcode == 256)
					{
						status = -1;
						return 0;
					}
					lzKay = (byte) lzOldcode;
					lzFinchar = lzKay;
					state = 1;
					return lzKay;

				case 1:
					lzIncode = getCode();
					if (lzIncode == 256)
					{
						state = 0;
						status = -1;
						return 0;
					}

					// Handle the KwKwK case in LZW
					boolean kwkwk = false;
					if (lzIncode >= lzNcodes)
					{
						kwkwk = true;
						code = lzOldcode;
						// Will push finchar after decompressing oldcode
					}
					else
					{
						code = lzIncode;
					}

					// Decompress the code
					while (code > 255)
					{
						push(lztab[code].ext);
						code = lztab[code].prefix;
					}

					// code is now a byte value (0-255)
					lzKay = (byte) code;

					// In KwKwK case, push the character that starts the string
					if (kwkwk)
					{
						push(lzKay);
					}

					lzFinchar = lzKay;
					state = 2;
					return lzKay;

				case 2:
					if (lzstack == 0)
					{
						// Add new entry to LZW table
						if (lzNcodes < lztab.length)
						{
							lztab[lzNcodes].prefix = lzOldcode;
							lztab[lzNcodes].ext = lzFinchar;
							lzNcodes++;
							if (lzNcodes % 256 == 0) {
							    System.out.println("LZW table size=" + lzNcodes + ", last incode=" + lzIncode + ", prefix=" + lzOldcode + ", ext=" + (lzFinchar & 0xFF));
							}
						}
						lzOldcode = lzIncode;
						state = 1;
						continue;
					}
					else
						return pop();
				default:
					status = -1;
					return 0;
				}
			}
		}

		// CRITICAL FIX: The getHeader() method structure should be:
		private boolean getHeader() throws IOException
		{
			bitBuf = 2;  // Initialize bitBuf first
			crc = 0;
			crc2 = 0;
			state = 0;
			ctrl = 254;

			// Reset LZ state variables
			lzOldcode = 0;
			lzIncode = 0;
			lzKay = 0;
			lzFinchar = 0;
			lzNcodes = 0;

			entry.version = getByte();
			entry.mode = getByte();
			entry.check = getWord();
			entry.size = getThree();
			entry.blocks = getWord();
			entry.type = getByte();
			entry.fnlen = getByte();

			if (entry.fnlen > 16)
				return false;

			for (int w = 0; w < entry.fnlen; w++)
				entry.name[w] = getByte();

			// Read ctrl byte for RLE modes BEFORE setting up bit reading
			if (entry.mode == 1 || entry.mode == 4 || entry.mode == 5)
			{
			    ctrl = getByte() & 0xFF;
			    System.out.println("Read ctrl=" + ctrl + " at pos=" + (pos-1));
			}

			if (entry.version > 1)
			{
				entry.rl = getByte();
				entry.date = getWord();
			}

			if (status == -1)
				return false;

			if (entry.version == 0 || entry.version > 2)
				return false;

			if (entry.version == 1 && entry.mode > 2)
				return false;

			if (entry.mode > 5)
				return false;

			// For Huffman modes, set up the Huffman tables (uses bit reading)
			if (entry.mode == 2 || entry.mode == 4)
			{
				hcount = 255;
				for (int w = 0; w < 256; w++)
				{
					hv[w] = (byte) w;
					hl[w] = 0;
					long mask1 = 1;
					for (int i = 1; i < 6; i++)
					{
						if (getBit() == 1)
							hl[w] |= (byte) mask1;
						mask1 <<= 1;
					}
					if (hl[w] > 24)
						return false;
					hc[w] = 0;
					if (hl[w] != 0)
					{
						int i = 0;
						mask1 = 1;
						while (i < hl[w])
						{
							if (getBit() == 1)
								hc[w] |= mask1;
							i++;
							mask1 <<= 1;
						}
					}
					else
						hcount--;
				}
				ssort();
			}

			// CRITICAL: Reset bitBuf AFTER reading ctrl for LZW modes
			// Use standard initialization
			if (entry.mode == 3 || entry.mode == 5)
				bitBuf = 2;  // Standard LSB-first marker

			final String legalTypes = "SPUR";
			return legalTypes.indexOf((char) (entry.type & 0xFF)) != -1
					|| legalTypes.indexOf((char) ((entry.type & 0xFF) - 32)) != -1;
		}

		private long getStartPos() throws IOException
		{
			final int savedPos = pos;
			pos = 0;
			int c = getByte() & 0xFF;
			pos = savedPos;
			if (c == 2)
				return 0;
			if (c != 1)
				return -1;

			pos = 0;
			getByte(); // Skip
			getWord();
			final int linenum = getWord();
			c = getByte() & 0xFF;

			if (c != 0x9E)
			{
				pos = savedPos;
				return 0;
			}

			getByte();
			final int cpu = getByte() & 0xFF;

			int skip = (linenum - 6) * 254;
			if (linenum == 15 && cpu == '7')
				skip -= 1;

			pos = savedPos;
			return skip;
		}

		private int getCode() throws IOException
		{
			//System.out.println("getCode: cdlen=" + cdlen + ", pos=" + pos + ", bitBuf=" + Integer.toHexString(bitBuf));
			code = 0;
			int i = cdlen;
			while (i-- > 0)
				code = (code << 1) | getBit();

			if (code == 256 && entry.mode == 5)
			{
				i = 16;
				entry.check = 0;
				while (i-- > 0)
					entry.check = (entry.check << 1) | getBit();
				i = 24;
				entry.size = 0;
				while (i-- > 0)
					entry.size = (entry.size << 1) | getBit();
				i = 16;
				while (i-- > 0)
					getBit();

				// NEW: Update entry.blocks based on bytes consumed so far (for mode 5
				// alignment)
				final int consumed = pos - entryStart;
				entry.blocks = consumed / 254;
				if (consumed % 254 != 0)
					entry.blocks++;
			}

			if (cdlen < 12)
			{
				if (--wttcl == 0)
				{
					wtcl <<= 1;
					cdlen++;
					wttcl = wtcl;
				}
			}
			System.out.println("getCode result: " + code);
			return code;
		}


		private void push(final byte c)
		{
			if (lzstack >= stack.length)
			{
				status = -1;
				return;
			}
			stack[lzstack++] = c;
		}

		private byte pop()
		{
			if (lzstack == 0)
			{
				status = -1;
				return 0;
			}
			return stack[--lzstack];
		}

		private void updateChecksum(final byte c)
		{
			final int val = c & 0xFF;
			int add;
			if (entry.version == 1)
				add = val;
			else
			{
				crc2 = (crc2 + 1) & 0xFF;
				final int xor = crc2;
				add = val ^ xor;
			}
			crc = (crc + add) & 0xFFFFL;
		}

		private byte unpack() throws IOException
		{
			switch (entry.mode)
			{
			case 0:
			case 1:
				return getByte();
			case 2:
			case 4:
				return huffin();
			case 3:
			case 5:
				return unc();
			default:
				status = -1;
				return 0;
			}
		}

		public List<FileInfo> getContents() throws IOException
		{
			final List<FileInfo> list = new ArrayList<>();
			final long temp = getStartPos();
			if (temp < 0)
				throw new IOException("Not a Commodore ARC or SDA.");
			pos = (int) temp;

			while (true)
			{
				entryStart = pos;
				if (!getHeader())
					break; // Exit if no more valid headers

				long length = entry.size;
				if (entry.mode == 5)
					length = 0xFFFFFFL;
				//TODO:BZ:DELME
				System.out.println("Starting decompression at pos=" + pos + ", expected length=" + length);
				final ByteArrayOutputStream baos = new ByteArrayOutputStream((int) length);
				while (baos.size() < length)
				{
					byteCount++;
					if (byteCount % 1024 == 0) {
					    System.out.println("Processed " + byteCount + " bytes, current crc=" + (crc & 0xFFFF));
					}
					byte c = unpack();
					if (status == -1)
						break;
					if ((entry.mode == 1 || entry.mode == 4 || entry.mode == 5) && c == (byte) ctrl)
					{
						int count = unpack() & 0xFF;
						c = unpack();
						if (status == -1)
							break;
						if (count == 0)
							count = entry.version == 1 ? 255 : 256;
						while (--count >= 0)
						{
							updateChecksum(c);
							baos.write(c);
						}
					} else
					{
						updateChecksum(c);
						baos.write(c);
					}
				}

				final byte[] fileData = baos.toByteArray();
				//TODO:BZ:DELME
				System.out.println("Decompression done: actual length=" + baos.size() + ", final pos=" + pos +
				                   ", calculated crc=" + (crc & 0xFFFF) + ", expected=" + entry.check);
				// Hex dump first 16 bytes of decompressed data
				System.out.print("First 16 decompressed bytes (hex): ");
				for (int i = 0; i < Math.min(16, fileData.length); i++) {
				    System.out.printf("%02X ", fileData[i] & 0xFF);
				}; System.out.println();
				final FileInfo file = new FileInfo();
				file.fileName = new String(entry.name, 0, entry.fnlen);
				file.filePath = new String(entry.name, 0, entry.fnlen);
				file.rawFileName = Arrays.copyOf(entry.name, entry.fnlen);
				char typChar = (char) (entry.type & 0xFF);
				if (typChar >= 'a' && typChar <= 'z')
					typChar = (char) (typChar - 32);
				file.fileType = FileType.fileType("" + typChar);
				file.size = fileData.length;
				file.feblocks = entry.blocks;
				file.data = fileData;
				if (((int) (crc & 0xFFFF) ^ entry.check) != 0)
					throw new IOException("Checksum error for " + file.fileName + " in ARC file.");
				list.add(file);
				pos = entryStart + entry.blocks * 254;
				if (pos > data.length)
				{
					status = -1;
					break;
				}
			}
			return list;
		}
	}

	public static List<FileInfo> getARCDeepContents(final byte[] data) throws IOException
	{
		final Decompressor decompressor = new Decompressor(data);
		return decompressor.getContents();
	}
}