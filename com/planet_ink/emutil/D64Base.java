package com.planet_ink.emutil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.TreeSet;

import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import com.planet_ink.emutil.CBMDiskImage.FileInfo;
import com.planet_ink.emutil.CBMDiskImage.FileType;

/*
Copyright 2016-2024 Bo Zimmerman

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
public class D64Base
{
	final static String EMUTIL_VERSION = "3.03";
	final static String EMUTIL_AUTHOR = "2024 Bo Zimmerman";

	final static String spaces="                                                        "
							 + "                                                        ";

	final static int MAGIC_MAX = 16 * 1024 * 1024;

	public final static int	PF_READINSIDE	= 1;
	public final static int	PF_NOERRORS		= 2;
	public final static int	PF_RECURSE		= 3;

	protected static TreeSet<String> repeatedErrors = new TreeSet<String>();

	protected static void errMsg(final String errMsg)
	{
		if(!repeatedErrors.contains(errMsg))
		{
			repeatedErrors.add(errMsg);
			System.err.println(errMsg);
		}
	}

	protected static byte[] numToBytes(final int x)
	{
		final byte[] result = new byte[4];
		result[3] = (byte) (x >> 24);
		result[2] = (byte) (x >> 16);
		result[1] = (byte) (x >> 8);
		result[0] = (byte) (x /*>> 0*/);
		return result;
	}

	public static byte tobyte(final int b)
	{
		return (byte)(0xFF & b);
	}

	protected static final int[] petToAscTable = new int[]
	{
		0x00,0x01,0x02,0x03,0x04,0x05,0x06,0x07,0x14,0x09,0x0d,0x11,0x93,0x0a,0x0e,0x0f,
		0x10,0x0b,0x12,0x13,0x08,0x15,0x16,0x17,0x18,0x19,0x1a,0x1b,0x1c,0x1d,0x1e,0x1f,
		0x20,0x21,0x22,0x23,0x24,0x25,0x26,0x27,0x28,0x29,0x2a,0x2b,0x2c,0x2d,0x2e,0x2f,
		0x30,0x31,0x32,0x33,0x34,0x35,0x36,0x37,0x38,0x39,0x3a,0x3b,0x3c,0x3d,0x3e,0x3f,
		0x40,0x61,0x62,0x63,0x64,0x65,0x66,0x67,0x68,0x69,0x6a,0x6b,0x6c,0x6d,0x6e,0x6f,
		0x70,0x71,0x72,0x73,0x74,0x75,0x76,0x77,0x78,0x79,0x7a,0x5b,0x5c,0x5d,0x5e,0x5f,
		0x60,0x41,0x42,0x43,0x44,0x45,0x46,0x47,0x48,0x49,0x4a,0x4b,0x4c,0x4d,0x4e,0x4f,
		0x50,0x51,0x52,0x53,0x54,0x55,0x56,0x57,0x58,0x59,0x5a,0x7b,0x7c,0x7d,0x7e,0x7f,
		0x80,0x81,0x82,0x83,0x84,0x85,0x86,0x87,0x88,0x89,0x8a,0x8b,0x8c,0x8d,0x8e,0x8f,
		0x90,0x91,0x92,0x0c,0x94,0x95,0x96,0x97,0x98,0x99,0x9a,0x9b,0x9c,0x9d,0x9e,0x9f,
		0x20,0xa1,0xa2,0xa3,0xa4,0xa5,0xa6,0xa7,0xa8,0xa9,0xaa,0xab,0xac,0xad,0xae,0xaf,
		0xb0,0xb1,0xb2,0xb3,0xb4,0xb5,0xb6,0xb7,0xb8,0xb9,0xba,0xbb,0xbc,0xbd,0xbe,0xbf,
		0x60,0x41,0x42,0x43,0x44,0x45,0x46,0x47,0x48,0x49,0x4a,0x4b,0x4c,0x4d,0x4e,0x4f,
		0x50,0x51,0x52,0x53,0x54,0x55,0x56,0x57,0x58,0x59,0xda,0xdb,0xdc,0xdd,0xde,0xdf,
		0xa0,0xa1,0xa2,0xa3,0xa4,0xa5,0xa6,0xa7,0xa8,0xa9,0xaa,0xab,0xac,0xad,0xae,0xaf,
		0xb0,0xb1,0xb2,0xb3,0xb4,0xb5,0xb6,0xb7,0xb8,0xb9,0xba,0xbb,0xbc,0xbd,0xbe,0xbf
	};

	protected static final int[] ascToPetTable = new int[]
	{
		0x00,0x01,0x02,0x03,0x04,0x05,0x06,0x07,0x14,0x20,0x0a,0x11,0x93,0x0d,0x0e,0x0f,
		0x10,0x0b,0x12,0x13,0x08,0x15,0x16,0x17,0x18,0x19,0x1a,0x1b,0x1c,0x1d,0x1e,0x1f,
		0x20,0x21,0x22,0x23,0x24,0x25,0x26,0x27,0x28,0x29,0x2a,0x2b,0x2c,0x2d,0x2e,0x2f,
		0x30,0x31,0x32,0x33,0x34,0x35,0x36,0x37,0x38,0x39,0x3a,0x3b,0x3c,0x3d,0x3e,0x3f,
		0x40,0xc1,0xc2,0xc3,0xc4,0xc5,0xc6,0xc7,0xc8,0xc9,0xca,0xcb,0xcc,0xcd,0xce,0xcf,
		0xd0,0xd1,0xd2,0xd3,0xd4,0xd5,0xd6,0xd7,0xd8,0xd9,0xda,0x5b,0x5c,0x5d,0x5e,0x5f,
		0xc0,0x41,0x42,0x43,0x44,0x45,0x46,0x47,0x48,0x49,0x4a,0x4b,0x4c,0x4d,0x4e,0x4f,
		0x50,0x51,0x52,0x53,0x54,0x55,0x56,0x57,0x58,0x59,0x5a,0xdb,0xdc,0xdd,0xde,0xdf,
		0x80,0x81,0x82,0x83,0x84,0x85,0x86,0x87,0x88,0x89,0x8a,0x8b,0x8c,0x8d,0x8e,0x8f,
		0x90,0x91,0x92,0x0c,0x94,0x95,0x96,0x97,0x98,0x99,0x9a,0x9b,0x9c,0x9d,0x9e,0x9f,
		0xa0,0xa1,0xa2,0xa3,0xa4,0xa5,0xa6,0xa7,0xa8,0xa9,0xaa,0xab,0xac,0xad,0xae,0xaf,
		0xb0,0xb1,0xb2,0xb3,0xb4,0xb5,0xb6,0xb7,0xb8,0xb9,0xba,0xbb,0xbc,0xbd,0xbe,0xbf,
		0xc0,0xc1,0xc2,0xc3,0xc4,0xc5,0xc6,0xc7,0xc8,0xc9,0xca,0xcb,0xcc,0xcd,0xce,0xcf,
		0xd0,0xd1,0xd2,0xd3,0xd4,0xd5,0xd6,0xd7,0xd8,0xd9,0xda,0xdb,0xdc,0xdd,0xde,0xdf,
		0xe0,0xe1,0xe2,0xe3,0xe4,0xe5,0xe6,0xe7,0xe8,0xe9,0xea,0xeb,0xec,0xed,0xee,0xef,
		0xf0,0xf1,0xf2,0xf3,0xf4,0xf5,0xf6,0xf7,0xf8,0xf9,0xfa,0xfb,0xfc,0xfd,0xfe,0xff
	};

	/**
	 * A Loose File is one that can not be reduced any further,
	 * or is too difficult to.  It must also be C= related, of
	 * course.
	 *
	 * @author BZ
	 *
	 */
	public enum LooseCBMFileType
	{
		PRG {
			public String toString() {
				return ".PRG";
			}
		},
		SEQ {
			public String toString() {
				return ".SEQ";
			}
		},
		CVT {
			public String toString() {
				return ".CVT";
			}
		},
		SDA {
			public String toString() {
				return ".SDA";
			}
		},
		SFX {
			public String toString() {
				return ".SFX";
			}
		},
		ARC {
			public String toString() {
				return ".ARC";
			}
		}
	}

	protected static char convertToPetscii(final byte b)
	{
		if(b<65) return (char)b;
		if(b<91) return Character.toLowerCase((char)b);
		if(b<192) return (char)b;
		if(b<219) return Character.toUpperCase((char)(b-128));
		return (char)(b-128);
	}

	protected static char convertToAscii(int b)
	{
		if((b<0)||(b>256))
			b = (byte)(Math.round(Math.abs(b)) & 0xff);
		return (char)(petToAscTable[b] & 0xff);
	}

	public static final String[] HEX=new String[256];
	public static final Hashtable<String,Short> ANTI_HEX=new Hashtable<String,Short>();
	public static final String HEX_DIG="0123456789ABCDEF";
	static
	{
		for(int h=0;h<16;h++)
		{
			for(int h2=0;h2<16;h2++)
			{
				HEX[(h*16)+h2]=""+HEX_DIG.charAt(h)+HEX_DIG.charAt(h2);
				ANTI_HEX.put(HEX[(h*16)+h2],new Short((short)((h*16)+h2)));
			}
		}
	}
	public static String toHex(final byte b)
	{
		return HEX[unsigned(b)];
	}

	public static String toHex(final byte[] buf)
	{
		final StringBuffer ret=new StringBuffer("");
		for(int b=0;b<buf.length;b++)
			ret.append(toHex(buf[b]));
		return ret.toString();
	}

	public static short fromHex(final String hex)
	{
		return (ANTI_HEX.get(hex)).shortValue();
	}

	public static short unsigned(final byte b)
	{
		return (short)(0xFF & b);
	}

	public static boolean isInteger(final String s)
	{
		try
		{
			final int x=Integer.parseInt(s);
			return x>0;
		}
		catch(final Exception e)
		{
			return false;
		}

	}

	protected static LooseCBMFileType getLooseImageTypeAndGZipped(final String fileName)
	{
		for(final LooseCBMFileType img : LooseCBMFileType.values())
		{
			final String uf = fileName.toUpperCase();
			if(uf.endsWith(img.toString())
			||uf.endsWith(img.toString()+".GZ"))
			{
				final LooseCBMFileType type=img;
				return type;
			}
			else
			if(uf.endsWith(",S"))
				return LooseCBMFileType.SEQ;
			else
			if(uf.endsWith(",P"))
				return LooseCBMFileType.PRG;
		}
		return null;
	}

	protected static FileInfo getLooseFile(final File F1) throws IOException
	{
		FileInputStream fi=null;
		try
		{
			fi=new FileInputStream(F1);
			return getLooseFile(fi, F1.getName(), (int)F1.length());
		}
		finally
		{
			if(fi != null)
				fi.close();
		}
	}

	protected static LooseCBMFileType getLooseImageTypeAndGZipped(final File F)
	{
		if(F==null)
			return null;
		return getLooseImageTypeAndGZipped(F.getName());
	}

	protected static LooseCBMFileType getLooseImageTypeAndGZipped(final IOFile F)
	{
		if(F==null)
			return null;
		return getLooseImageTypeAndGZipped(F.getName());
	}

	protected static FileInfo getLooseFile(final InputStream fin, final String fileName, int fileLen) throws IOException
	{
		final LooseCBMFileType typ = getLooseImageTypeAndGZipped(fileName);
		byte[] filedata;
		filedata = new byte[fileLen];
		int lastLen = 0;
		while(lastLen < fileLen)
		{
			final int readBytes = fin.read(filedata, lastLen, fileLen-lastLen);
			if(readBytes < 0)
				break;
			lastLen += readBytes;
		}
		if(fileName.toLowerCase().endsWith(".gz"))
		{

			final ByteArrayInputStream bfin = new ByteArrayInputStream(Arrays.copyOf(filedata, lastLen));
			@SuppressWarnings("resource")
			final GzipCompressorInputStream in = new GzipCompressorInputStream(bfin);
			final byte[] lbuf = new byte[4096];
			int read=in.read(lbuf);
			final ByteArrayOutputStream bout=new ByteArrayOutputStream(lastLen*2);
			while(read >= 0)
			{
				bout.write(lbuf,0,read);
				read=in.read(lbuf);
			}
			//in.close(); dont do it -- this might be from a zip
			fileLen=bout.toByteArray().length;
			lastLen=fileLen;
			filedata=bout.toByteArray();
		}
		final FileInfo file = new FileInfo();
		if(typ == null)
		{
			file.fileName = fileName;
			file.fileType = FileType.SEQ;
		}
		else
		switch(typ)
		{
		case CVT:
			file.fileName = fileName;
			file.fileType = FileType.USR;
			break;
		case SDA:
		case SFX:
		case ARC:
			file.fileName = fileName;
			file.fileType = FileType.PRG;
			break;
		case PRG:
			file.fileName = fileName.substring(0,fileName.length()-4);
			file.fileType = FileType.PRG;
			break;
		case SEQ:
			file.fileName = fileName.substring(0,fileName.length()-4);
			file.fileType = FileType.SEQ;
			break;
		}
		final ByteArrayOutputStream fout = new ByteArrayOutputStream();
		for(int i=0;i<file.fileName.length();i++)
		{
			final byte b = (byte)(ascToPetTable[(file.fileName.charAt(i) & 0xff)] & 0xff);
			if(b != 0)
				fout.write(b);
		}
		file.rawFileName = fout.toByteArray();
		if(lastLen < fileLen && (fileLen >= MAGIC_MAX))
		{
			file.size=lastLen;
			file.feblocks=(int)Math.round(Math.ceil(lastLen / 254.0));
			file.data = Arrays.copyOf(filedata, lastLen);
		}
		else
		{
			file.size = fileLen;
			file.feblocks=(int)Math.round(Math.ceil(fileLen / 254.0));
			file.data = filedata;
		}
		return file;
	}

}
