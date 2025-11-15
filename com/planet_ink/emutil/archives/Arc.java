package com.planet_ink.emutil.archives;

import java.io.*;
import java.util.*;

import com.planet_ink.emutil.CBMDiskImage.FileInfo;
import com.planet_ink.emutil.CBMDiskImage.FileType;
import com.planet_ink.emutil.D64Base;

/**
 * ARC/SDA (C64/C128) archive extractor
 * Derived from Chris Smeets' MS-DOS utility a2l that converts Commodore
 * ARK, ARC and SDA files to LZH using LHARC 1.12. Optimized for speed,
 * converted to standard C and adapted to the cbmconvert package by
 * Marko Mäkelä.  Straight Java port by Bo Zimmerman.
 *
 *  Original version: a2l.c       March   1st, 1990   Chris Smeets
 *  Unix port:        unarc.c     August 28th, 1993   Marko Mäkelä
 *  Restructured for cbmconvert 2.0 and 2.1 by Marko Mäkelä
 *  Java port:        Aarc.java   November 24th, 2025   Bo Zimmerman
*/
public class Arc
{
	public static class Entry
	{
		int		version;
		int		mode;
		long	check;
		long	size;
		long	blocks;
		int		type;
		int		fnlen;
		String	name;
		byte[]  rawName = new byte[17];
		int		rl;
		long	date;
		byte[]	buffer	= null;
	}

	public static class Lz
	{
		long	prefix;
		int		ext;
	}

	private static final int EOF = -1;

	private int				status;
	private long			filePos;
	private byte[]			data;
	private int				position;
	private long			bitBuf;
	private long			crc;
	private int				crc2;
	private final long[]	hc		= new long[256];
	private final int[]		hl		= new int[256];
	private final int[]		hv		= new int[256];
	private int				hcount;
	private long			ctrl;
	private Entry			entry	= new Entry();
	private final Lz[]		lzTab	= new Lz[4096];
	private final byte[]	stack	= new byte[512];
	private int				state	= 0;
	private int				lzStack	= 0;
	private int				cdLen;
	private long			code;
	private int				wtCl;
	private int				wtTcl;

	private long	oldCode;
	private long	inCode;
	private int		kay;
	private long	omega;
	private int		finChar;
	private long	nCodes;

	public Arc()
	{
		for(int i = 0; i < 4096; i++)
			lzTab[i] = new Lz();
	}

	private int readByte()
	{
		if(position >= data.length)
			return -1;
		return data[position++] & 0xFF;
	}

	private int getByte() throws IOException
	{
		if(status == EOF)
			return 0;
		final int b = readByte();
		if(b == -1)
		{
			status = EOF;
			return 0;
		}
		status = 0;
		return b;
	}

	private long getWord() throws IOException
	{
		if(status == EOF)
			return 0;
		final int low = readByte();
		if(low == -1)
		{
			status = EOF;
			return 0;
		}
		final int high = readByte();
		if(high == -1)
		{
			status = EOF;
			return 0;
		}
		status = 0;
		return (low | (high << 8)) & 0xFFFFL;
	}

	private long getThree() throws IOException
	{
		if(status == EOF)
			return 0;
		final int b1 = readByte();
		if(b1 == -1)
		{
			status = EOF;
			return 0;
		}
		final int b2 = readByte();
		if(b2 == -1)
		{
			status = EOF;
			return 0;
		}
		final int b3 = readByte();
		if(b3 == -1)
		{
			status = EOF;
			return 0;
		}
		status = 0;
		return (b1 | (b2 << 8) | (b3 << 16)) & 0xFFFFFFL;
	}

	private int getBit() throws IOException
	{
		final long result = bitBuf >>>= 1;
		if(result == 1)
			return (int) (1 & (bitBuf = getByte() | 0x100L));
		else
			return (int) (1 & result);
	}

	private void ssort()
	{
		long m = hl.length;
		long h, i, j, k;
		while((m >>= 1) > 0)
		{
			k = hl.length - m;
			j = 1;
			do
			{
				i = j;
				do
				{
					h = i + m;
					if(hl[(int) h - 1] > hl[(int) i - 1])
					{
						final long t = hc[(int) i - 1];
						hc[(int) i - 1] = hc[(int) h - 1];
						hc[(int) h - 1] = t;
						int u = hv[(int) i - 1];
						hv[(int) i - 1] = hv[(int) h - 1];
						hv[(int) h - 1] = u;
						u = hl[(int) i - 1];
						hl[(int) i - 1] = hl[(int) h - 1];
						hl[(int) h - 1] = u;
						i -= m;
					}
					else
						break;
				} while(i >= 1);
				j += 1;
			} while(j <= k);
		}
	}

	private boolean getHeader() throws IOException
	{
		int w, i;
		final String legalTypes = "SPUR";
		long mask;

		if(status == EOF)
			return false;

		bitBuf = 2L;
		crc = 0L;
		crc2 = 0;
		state = 0;
		ctrl = 254L;

		entry.version = getByte();
		entry.mode = getByte();
		entry.check = getWord();
		entry.size = getThree();
		entry.blocks = getWord();
		entry.type = getByte();
		entry.fnlen = getByte();

		if(entry.fnlen > 16)
			return false;

		entry.rawName = new byte[entry.fnlen];
		for(w = 0; w < entry.fnlen; w++)
			entry.rawName[w] = (byte)(getByte() & 0xFF);
		entry.name = "";
		for(w = 0; w < entry.fnlen; w++)
			entry.name += D64Base.convertToAscii(entry.rawName[w]);

		if(entry.version > 1)
		{
			entry.rl = getByte();
			entry.date = getWord();
		}

		if(status == EOF)
			return false;

		if(entry.version == 0 || entry.version > 2)
			return false;

		if(entry.version == 1)
		{
			if(entry.mode > 2)
				return false;
		}

		if(entry.mode == 1)
			ctrl = getByte();

		if(entry.mode > 5)
			return false;

		if(entry.mode == 2 || entry.mode == 4)
		{
			hcount = 255;
			for(w = 0; w < 256; w++)
			{
				hv[w] = w;
				hl[w] = 0;
				mask = 1;
				for(i = 1; i < 6; i++)
				{
					if(getBit() == 1)
						hl[w] |= (int) mask;
					mask <<= 1;
				}
				if(hl[w] > 24)
					return false;
				hc[w] = 0;
				if(hl[w] != 0)
				{
					i = 0;
					mask = 1;
					while(i < hl[w])
					{
						if(getBit() == 1)
							hc[w] |= mask;
						i++;
						mask <<= 1;
					}
				}
				else
					hcount--;
			}
			ssort();
		}

		return legalTypes.indexOf((char) entry.type) >= 0;
	}

	private long getStartPos() throws IOException
	{
		int c;
		int cpu;
		long linenum;
		long skip;

		position = 0;
		status = 0;

		c = getByte();
		if(c == 2)
			return 0L;
		if(c != 1)
			return -1L;

		getByte();
		getWord();
		linenum = getWord();
		c = getByte();

		if(c != 0x9E)
			return 0L;

		c = getByte();
		cpu = getByte();

		skip = (linenum - 6) * 254;
		if(linenum == 15 && cpu == '7')
			skip -= 1;

		return skip;
	}

	private void push(final int c) throws IOException
	{
		if(lzStack >= stack.length)
			throw new IOException("PushError");
		stack[lzStack++] = (byte) c;
	}

	private int pop() throws IOException
	{
		if(lzStack == 0)
			throw new IOException("PopError");
		return stack[--lzStack] & 0xFF;
	}

	private long getCode() throws IOException
	{
		int i;
		long blocks;

		code = 0L;
		i = cdLen;
		while(i-- > 0)
			code = (code << 1) | getBit();

		if(code == 256 && entry.mode == 5)
		{
			i = 16;
			entry.check = 0L;
			while(i-- > 0)
				entry.check = (entry.check << 1) | getBit();
			i = 24;
			entry.size = 0L;
			while(i-- > 0)
				entry.size = (entry.size << 1) | getBit();
			i = 16;
			while(i-- > 0)
				getBit();
			blocks = position - filePos;
			entry.blocks = blocks / 254;
			if(blocks % 254 != 0)
			{
				entry.blocks++;
			}
		}

		if(cdLen < 12)
		{
			if(--wtTcl == 0)
			{
				wtCl <<= 1;
				cdLen++;
				wtTcl = wtCl;
			}
		}

		return code;
	}

	private int unc() throws IOException
	{
		switch (state)
		{
		case 0:
			lzStack = 0;
			nCodes = 258L;
			wtCl = 256;
			wtTcl = 254;
			cdLen = 9;
			oldCode = getCode();
			if(oldCode == 256)
			{
				status = EOF;
				return 0;
			}
			kay = (int) oldCode;
			finChar = kay;
			state = 1;
			return kay;
		case 1:
			inCode = getCode();
			if(inCode == 256)
			{
				state = 0;
				status = EOF;
				return 0;
			}
			if(inCode >= nCodes)
			{
				kay = finChar;
				push(kay);
				code = oldCode;
				omega = oldCode;
				inCode = nCodes;
			}
			while(code > 255)
			{
				push(lzTab[(int) code].ext);
				code = lzTab[(int) code].prefix;
			}
			finChar = kay = (int) code;
			state = 2;
			return kay;
		case 2:
			if(lzStack == 0)
			{
				omega = oldCode;
				if(nCodes < lzTab.length)
				{
					lzTab[(int) nCodes].prefix = omega;
					lzTab[(int) nCodes].ext = kay;
					nCodes++;
				}
				oldCode = inCode;
				state = 1;
				return unc();
			}
			else
				return pop();
		default:
			status = EOF;
			return 0;
		}
	}

	private void updateChecksum(int c)
	{
		c &= 0xFF;
		if(entry.version == 1)
			crc = (crc + c) & 0xFFFFFFFFL;
		else
			crc = (crc + (c ^ (crc2 = (crc2 + 1) & 0xFF))) & 0xFFFFFFFFL;
	}

	private int huffin() throws IOException
	{
		long hcode = 0;
		long mask = 1;
		int size = 1;
		int now = hcount;

		do
		{
			if(getBit() == 1)
				hcode |= mask;
			while(hl[now] == size)
			{
				if(hc[now] == hcode)
					return hv[now];
				if(--now < 0)
				{
					status = EOF;
					return 0;
				}
			}
			size++;
			mask <<= 1;
		} while(size < 24);

		status = EOF;
		return 0;
	}

	private int unPack() throws IOException
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
			status = EOF;
			return 0;
		}
	}

	private List<Entry> readArc(final byte[] data, final BitSet parseFlags) throws IOException
	{
		final List<Entry> files = new ArrayList<Entry>();
		this.data = data;
		this.position = 0;

		final long temp = getStartPos();
		if(temp < 0)
			throw new IOException("Not a Commodore ARC or SDA.");
		position = (int) temp;

		filePos = position;

		while(getHeader())
		{
			long length = entry.size;
			if(entry.mode == 5)
				length = 65536;
			final byte[] buffer = new byte[(int) length];
			int bufIndex = 0;
			while(bufIndex < length)
			{
				int c = unPack();
				if(status == EOF)
					break;
				if(entry.mode != 0 && entry.mode != 2 && c == ctrl)
				{
					int count = unPack();
					if(status == EOF)
						break;
					c = unPack();
					if(status == EOF)
						break;
					if(count == 0)
						count = entry.version == 1 ? 255 : 256;
					for(int ii = 0; ii < count; ii++)
					{
						if(bufIndex >= length)
							break;
						updateChecksum(buffer[bufIndex] = (byte) c);
						bufIndex++;
					}
				}
				else
				{
					if(bufIndex >= length)
						break;
					updateChecksum(buffer[bufIndex] = (byte) c);
					bufIndex++;
				}
			}

			if((((crc ^ entry.check) & 0xFFFFL) != 0)
			&&(!parseFlags.get(D64Base.PF_NOERRORS)))
				throw new IOException(entry.name+": Checksum error!");

			entry.buffer = buffer;
			files.add(entry);
			filePos += entry.blocks * 254;
			position = (int) filePos;
			entry	= new Entry();
		}
		return files;
	}

	public static List<FileInfo> getARCDeepContents(final byte[] data, final BitSet parseFlags) throws IOException
	{
		final Arc decompressor = new Arc();
		final List<Entry> entries = decompressor.readArc(data, parseFlags);
		final List<FileInfo> files = new Vector<FileInfo>();
		for(final Entry entry : entries)
		{
			final FileInfo file = new FileInfo();
			file.data = entry.buffer;
			file.fileName = entry.name;
			file.filePath = entry.name;
			file.rawFileName = entry.rawName;//.copyOf(entry.name, entry.fnlen);
			file.fileType = FileType.fileType(""+(char)entry.type);
			file.size = entry.buffer.length;
			file.feblocks = 1+(int)Math.round(Math.floor(entry.buffer.length / 254.0));
			files.add(file);
		}
		return files;
	}
}
