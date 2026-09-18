package com.planet_ink.emutil.archives;
import java.io.*;
import java.util.*;

import org.apache.commons.compress.archivers.zip.*;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import com.planet_ink.emutil.CBMDiskImage.FileInfo;
import com.planet_ink.emutil.CBMDiskImage.FileType;
import com.planet_ink.emutil.D64Base;
import com.planet_ink.emutil.GeoMod;

/*
Copyright 2026-2026 Bo Zimmerman

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

/**
 * Expands a recognized archive or container file into the individual CBM
 * files inside it, as FileInfo objects ready for insertion into a disk
 * image.  Recognized containers are:
 *
 *   .gz  -- a gzip'd single file (by extension or 1F 8B magic); the inner
 *           file keeps its name minus the ".gz"
 *   .zip -- a zip archive (by extension or PK magic); every entry is
 *           expanded separately
 *   .lnx -- a Lynx archive, expanded through Lynx.getLNXDeepContents()
 *   .cvt -- a GEOS convert file; kept whole (block 0/1 header + VLIR table
 *           + data) so it can be inserted as a real GEOS file
 *
 * Containers nest (a zip inside a gz, a cvt inside an lnx) up to MAX_DEPTH
 * levels, with a total expansion cap of MAGIC_MAX bytes.  GEOS convert data
 * is detected by signature, so a USR file inside an archive that is really
 * a convert file is handled as well.
 */
public class FileExtractor
{
	public static final int	MAX_DEPTH	= 5;

	private FileExtractor()
	{
	}

	public static boolean isGZipped(final String name, final byte[] data)
	{
		if(name.toLowerCase().endsWith(".gz"))
			return true;
		return (data.length > 2)
			&& (data[0] == 0x1f) && (data[1] == (byte)0x8b) && ((data[2] & 0xff) == 0x08);
	}

	public static boolean isZipped(final String name, final byte[] data)
	{
		if(name.toLowerCase().endsWith(".zip"))
			return true;
		return (data.length > 3)
			&& (data[0] == 'P') && (data[1] == 'K') && (data[2] == 3) && (data[3] == 4);
	}

	public static boolean isContainer(final String name, final byte[] data)
	{
		return isGZipped(name, data)
			|| isZipped(name, data)
			|| name.toLowerCase().endsWith(".lnx")
			|| GeoMod.isCvt(data);
	}

	/**
	 * Expand the given container file into the CBM files inside it.
	 *
	 * @param source the archive/convert file to expand
	 * @return the extracted files, with fileName, fileType and data set
	 * @throws IOException if the file is missing, is not a recognized
	 * container, or is corrupt
	 */
	public static List<FileInfo> extract(final File source) throws IOException
	{
		if((!source.isFile())||(!source.exists())||(!source.canRead()))
			throw new IOException("File not found: "+source.getAbsolutePath());
		final byte[] data;
		try(final InputStream in = new FileInputStream(source))
		{
			data = readAll(in);
		}
		if(!isContainer(source.getName(), data))
			throw new IOException("Not a recognized archive (gz, zip, lnx, cvt): "+source.getAbsolutePath());
		final List<FileInfo> out = new ArrayList<FileInfo>();
		extract(source.getName(), data, 0, new long[1], out);
		return out;
	}

	private static void extract(final String name, final byte[] data, final int depth,
								final long[] total, final List<FileInfo> out) throws IOException
	{
		if(depth >= MAX_DEPTH)
			throw new IOException("Archive nesting too deep: "+name);
		total[0] += data.length;
		if(total[0] > D64Base.MAGIC_MAX)
			throw new IOException("Archive expansion too large: "+name);

		if(isGZipped(name, data))
		{
			String innerName = name;
			if(innerName.toLowerCase().endsWith(".gz"))
				innerName = innerName.substring(0, innerName.length()-3);
			final byte[] innerData;
			try(final InputStream in = new GzipCompressorInputStream(new ByteArrayInputStream(data)))
			{
				innerData = readAll(in);
			}
			catch(final IOException e)
			{
				throw new IOException("Bad gzip data in "+name+": "+e.getMessage());
			}
			extract(innerName, innerData, depth+1, total, out);
			return;
		}

		if(isZipped(name, data))
		{
			try(final ZipArchiveInputStream zin = new ZipArchiveInputStream(new ByteArrayInputStream(data)))
			{
				for(ZipArchiveEntry E = zin.getNextZipEntry(); E != null; E = zin.getNextZipEntry())
				{
					if(E.isDirectory())
						continue;
					extract(baseName(E.getName()), readAll(zin), depth+1, total, out);
				}
			}
			catch(final IOException e)
			{
				throw new IOException("Bad zip data in "+name+": "+e.getMessage());
			}
			return;
		}

		if(name.toLowerCase().endsWith(".lnx"))
		{
			for(final FileInfo f : Lynx.getLNXDeepContents(data))
			{
				if(GeoMod.isCvt(f.data))
					out.add(cvtFile(f.data));
				else
					out.add(f);
			}
			return;
		}

		if(GeoMod.isCvt(data))
		{
			out.add(cvtFile(data));
			return;
		}

		out.add(plainFile(name, data));
	}

	private static FileInfo cvtFile(final byte[] data) throws IOException
	{
		final GeoMod gm = GeoMod.fromData(data);
		final FileInfo f = new FileInfo();
		f.fileName = gm.getHeaderName();
		f.filePath = f.fileName;
		f.fileType = cbmFileType(data[0]);
		f.rawFileName = rawHeaderName(data);
		f.data = data;
		f.size = data.length;
		f.feblocks = (int)Math.round(Math.ceil(data.length / 254.0));
		return f;
	}

	private static FileInfo plainFile(final String name, final byte[] data)
	{
		final FileInfo f = new FileInfo();
		String nm = baseName(name);
		FileType t = null;
		final int e = nm.lastIndexOf('.');
		if(e >= 0)
			t = FileType.fileType(nm.substring(e+1));
		if(((t == FileType.PRG)||(t == FileType.SEQ))&&(e > 0))
			nm = nm.substring(0,e);
		f.fileName = nm;
		f.filePath = nm;
		f.fileType = (t == null) ? FileType.PRG : t;
		f.rawFileName = new byte[0];
		f.data = data;
		f.size = data.length;
		f.feblocks = (int)Math.round(Math.ceil(data.length / 254.0));
		return f;
	}

	private static FileType cbmFileType(final byte typeByte)
	{
		final int t = typeByte & 0x0f;
		final FileType[] types = FileType.values();
		if((t < types.length)&&(t >= 0))
			return types[t];
		return FileType.USR;
	}

	private static byte[] rawHeaderName(final byte[] cvtData)
	{
		int end = 3;
		for(int i=3;i<19;i++)
		{
			final int b = cvtData[i] & 0xff;
			if((b == 0)||(b == 0xa0))
				break;
			end = i+1;
		}
		return Arrays.copyOfRange(cvtData, 3, end);
	}

	private static String baseName(final String name)
	{
		int x = name.lastIndexOf('/');
		final int y = name.lastIndexOf('\\');
		if(y > x)
			x = y;
		if(x >= 0)
			return name.substring(x+1);
		return name;
	}

	private static byte[] readAll(final InputStream in) throws IOException
	{
		final ByteArrayOutputStream bout = new ByteArrayOutputStream();
		final byte[] buf = new byte[4096];
		int n;
		while((n = in.read(buf)) >= 0)
			bout.write(buf, 0, n);
		return bout.toByteArray();
	}
}
