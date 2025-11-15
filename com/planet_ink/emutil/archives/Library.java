package com.planet_ink.emutil.archives;

import java.io.*;
import java.util.*;

import com.planet_ink.emutil.CBMDiskImage.FileInfo;
import com.planet_ink.emutil.CBMDiskImage.FileType;
import com.planet_ink.emutil.D64Base;

public class Library
{

	private final byte[] data;
	private volatile int dptr;

	public Library(final byte[] data)
	{
		this.data = data;
		this.dptr = 0;
	}

	private byte[] readTo(final byte to, final BitSet parseFlags) throws IOException
	{
		if(dptr >= data.length)
			throw new IOException("No more data");
		final ByteArrayOutputStream bout = new ByteArrayOutputStream();
		while(dptr < data.length)
		{
			final byte b = data[dptr++];
			if(b == to)
				return bout.toByteArray();
			bout.write(b);
		}
		throw new IOException("Ran out of data");
	}

	public static String convertToAscii(final byte[] byts)
	{
		String str = "";
		for(final byte b : byts)
			str += D64Base.convertToAscii(b);
		return str;
	}

	private List<FileInfo> loadFiles(final BitSet parseFlags) throws IOException
	{
		final List<FileInfo> files = new Vector<FileInfo>();
		final byte[] sig = readTo((byte)0x20, parseFlags);
		if(!new String(sig).startsWith("DWB"))
			throw new IOException("Not an LBR");
		final int fct = Integer.parseInt(new String(readTo((byte)0x20, parseFlags)));
		readTo((byte)0x0d, parseFlags);
		for(int fn = 0;fn<fct;fn++)
		{
			final byte[] rawFilename = readTo((byte)0x0d, parseFlags);
			final String filename = convertToAscii(rawFilename);
			final String type = convertToAscii(readTo((byte)0x0d, parseFlags));
			readTo((byte)0x20, parseFlags);
			final int fsize = Integer.parseInt(new String(readTo((byte)0x20, parseFlags)));
			readTo((byte)0x0d, parseFlags);
			final FileInfo F = new FileInfo();
			F.fileName = filename;
			F.filePath = filename;
			F.rawFileName = rawFilename;
			F.size = fsize;
			F.fileType = FileType.fileType(type);
			F.feblocks = (int)Math.round(1.0 + Math.floor(F.size / 254.0));
			if(F.fileType == null)
				throw new IOException("Unknown FT "+type+" for "+F.fileName);
			files.add(F);
		}
		for(final FileInfo F : files)
		{
			if((dptr + F.size)>data.length)
				throw new IOException("Ran out of data on "+F.fileName);
			F.data = Arrays.copyOfRange(data, dptr, dptr + F.size);
		}
		return files;
	}

	public static List<FileInfo> getLNXDeepContents(final byte[] data, final BitSet parseFlags) throws IOException
	{
		return new Library(data).loadFiles(parseFlags);
	}
}
