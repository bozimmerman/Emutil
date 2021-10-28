package com.planet_ink.emutil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.Vector;

import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/*
Copyright 2016-2017 Bo Zimmerman

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

	final static int MAGIC_MAX = 16 * 1024 * 1024;

	public D64Base() {}

	final static String spaces="                                                                                                               ";

	protected static TreeSet<String> repeatedErrors = new TreeSet<String>();

	// todo: add file masks to options
	public enum IMAGE_TYPE {
		D64 {
			public String toString() {
				return ".D64";
			}
		},
		D71 {
			public String toString() {
				return ".D71";
			}
		},
		D81 {
			public String toString() {
				return ".D81";
			}
		},
		D80 {
			public String toString() {
				return ".D80";
			}
		},
		D82 {
			public String toString() {
				return ".D82";
			}
		},
		DNP {
			public String toString() {
				return ".DNP";
			}
		},
	};

	public enum LOOSE_IMAGE_TYPE {
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
		}
	}

	enum FileType
	{
		DEL,SEQ,PRG,USR,REL,CBM,DIR;
		public static FileType fileType(String s)
		{
			s=(s==null)?"":s.toUpperCase().trim();
			if(s.length()==0)
				return null;
			for(final FileType T : FileType.values())
				if(T.name().startsWith(s))
					return T;
			return null;
		}
	}

	public static class FileInfo
	{
		FileInfo parentF = null;
		String fileName = "";
		String filePath = "";
		FileType fileType = null;
		int size = 0;
		byte[] data = null;
		byte[] header = null;
		short[] dirLoc = new short[3];
		List<short[]> tracksNSecs = new ArrayList<short[]>();
		public String toString() { return filePath;}
		private long hash = -1;
		public long hash()
		{
			if(hash ==-1)
			{
				if(data != null)
				{
					hash=size;
					for(int bn=0;bn<data.length-1;bn+=2)
						hash ^= ( ((data[bn+1]) << 8) | (data[bn]));

				}
			}
			return hash;
		}

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

	protected static void errMsg(final String errMsg)
	{
		if(!repeatedErrors.contains(errMsg))
		{
			repeatedErrors.add(errMsg);
			System.err.println(errMsg);
		}
	}

	protected static int getImageSecsPerTrack(final IMAGE_TYPE type, final int t)
	{
		switch(type)
		{
		case D64:
		{
			if(t<18) return 21;
			if(t<25) return 19;
			if(t<31) return 18;
			return 17;
		}
		case D71:
		{
			if(t<18) return 21;
			if(t<25) return 19;
			if(t<31) return 18;
			if(t<36) return 17;
			if(t<53) return 21;
			if(t<60) return 19;
			if(t<66) return 18;
			return 17;
		}
		case D81:
			return 40;
		case DNP:
			return 256;
		case D80:
		case D82:
		{
			if(t<40) return 29;
			if(t<54) return 27;
			if(t<65) return 25;
			if(t<78) return 23;
			if(t<117) return 29;
			if(t<131) return 27;
			if(t<142) return 25;
			if(t<155) return 23;
			return 23;
		}
		}
		return -1;
	}

	public static int getImageTotalBytes(final IMAGE_TYPE type, final int fileSize)
	{
		final int ts=getImageNumTracks(type, fileSize);
		int total=0;
		for(int t=1;t<=ts;t++)
			total+=(256*getImageSecsPerTrack(type,t));
		return total;
	}

	public static int getImageDirTrack(final IMAGE_TYPE type)
	{
		switch(type)
		{
		case D64:
			return 18;
		case D71:
			return 18;
		case D81:
			return 40;
		case D80:
		case D82:
			return 39;
		case DNP:
			return 1;
		}
		return -1;
	}

	public static short getImageInterleave(final IMAGE_TYPE type)
	{
		switch(type)
		{
		case D64:
			return 10;
		case D71:
			return 5;
		case D81:
			return 1;
		case D80:
		case D82:
			return 10;
		case DNP:
			return 1;
		}
		return -1;
	}

	public static int getImageDirSector(final IMAGE_TYPE type)
	{
		switch(type)
		{
		case D64:
			return 0;
		case D71:
			return 0;
		case D81:
			return 0;
		case D80:
		case D82:
			return 0;
		case DNP:
			return 1;
		}
		return -1;
	}

	protected static int getImageNumTracks(final IMAGE_TYPE type, final long fileSize)
	{
		switch(type)
		{
		case D64:
			if(fileSize >= 349696)
				return 70;
			return 35;
		case D71:
			return 70;
		case D81:
			return 80;
		case D80:
			return 77;
		case D82:
			return 2*77;
		case DNP:
			return (int)(fileSize / 256 / 256);
		}
		return -1;
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

	protected static byte[][][] parseMap(final IMAGE_TYPE type, final byte[] buf, final int fileLength)
	{
		final int numTS=getImageNumTracks(type, fileLength);
		final byte[][][] tsmap=new byte[numTS+1][getImageSecsPerTrack(type,1)][256];
		int index=0;
		for(int t=1;t<=numTS;t++)
		{
			final int secs=getImageSecsPerTrack(type,t);
			for(int s=0;s<secs;s++)
			{
				for(int i=0;i<256;i++)
					tsmap[t][s][i]=buf[index+i];
				index+=256;
			}
		}
		return tsmap;
	}

	public static byte[][][] getDisk(final IMAGE_TYPE type, final File F, final int[] fileLen)
	{
		FileInputStream fi=null;
		try
		{
			fi=new FileInputStream(F);
			return getDisk(type, fi, F.getName(), (int)F.length(), fileLen);
		}
		catch(final IOException e)
		{
			e.printStackTrace(System.err);
			return new byte[D64Base.getImageNumTracks(type, (int)F.length())][255][256];
		}
		finally
		{
			if(fi != null)
			{
				try
				{
					fi.close();
				}
				catch(final Exception e)
				{}
			}
		}
	}

	public static byte[][][] getDisk(final IMAGE_TYPE type, final InputStream fin, final String fileName, final int fLen, final int[] fileLen)
	{
		int len=fLen;
		InputStream is=null;
		try
		{
			if(fileName.toUpperCase().endsWith(".GZ"))
			{
				@SuppressWarnings("resource")
				final GzipCompressorInputStream in = new GzipCompressorInputStream(fin);
				final byte[] lbuf = new byte[4096];
				int read=in.read(lbuf);
				final ByteArrayOutputStream bout=new ByteArrayOutputStream(len*2);
				while(read >= 0)
				{
					bout.write(lbuf,0,read);
					read=in.read(lbuf);
				}
				//in.close(); dont do it -- this might be from a zip
				len=bout.toByteArray().length;
				is=new ByteArrayInputStream(bout.toByteArray());
			}
			if(len == 0)
			{
				System.err.println("?: Error: Failed to read at ALL!");
				return new byte[D64Base.getImageNumTracks(type, len)][255][256];
			}
			final byte[] buf=new byte[getImageTotalBytes(type,len)];
			if(is == null)
				is=fin;
			int totalRead = 0;
			while((totalRead < len) && (totalRead < buf.length))
			{
				final int read = is.read(buf,totalRead,buf.length-totalRead);
				if(read>=0)
					totalRead += read;
			}
			if((fileLen != null)&&(fileLen.length>0))
				fileLen[0]=len;
			return parseMap(type,buf,len);
		}
		catch(final java.io.IOException e)
		{
			e.printStackTrace(System.err);
			return new byte[D64Base.getImageNumTracks(type, len)][255][256];
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

	public static byte tobyte(final int b)
	{
		return (byte)(0xFF & b);
	}

	public static byte[] getFileContent(final String fileName, final byte[][][] tsmap, int t, final int mt, int s, final List<short[]> secsUsed) throws IOException
	{
		final HashSet<byte[]> doneBefore=new HashSet<byte[]>();
		byte[] sector=null;
		final ByteArrayOutputStream out=new ByteArrayOutputStream();
		if(t>=tsmap.length)
			throw new IOException("Illegal Track "+t+" for "+fileName);
		if(s>=tsmap[t].length)
			throw new IOException("Illegal Sector ("+t+","+s+")"+" for "+fileName);
		while((t!=0)&&(!doneBefore.contains(tsmap[t][s]))&&(t<=mt))
		{
			if(secsUsed != null)
				secsUsed.add(new short[]{(short)t,(short)s});
			int maxBytes=255;
			sector=tsmap[t][s];
			if(sector[0]==0)
				maxBytes=unsigned(sector[1]);
			doneBefore.add(sector);
			for(int i=2;i<=maxBytes;i++)
				out.write(sector[i]);
			t=unsigned(sector[0]);
			s=unsigned(sector[1]);
			if(t==0)
				break;
			if(t>=tsmap.length)
				throw new IOException("Illegal Track "+t);
			if(s>=tsmap[t].length)
				throw new IOException("Illegal Sector ("+t+","+s+")");
		}
		return out.toByteArray();
	}

	public static void finishFillFileList(final FileInfo dirInfo, final String imgName, final int srcFLen, final IMAGE_TYPE type, final String prefix, final byte[][][] tsmap, final Set<byte[]> doneBefore, final List<FileInfo> finalData, int t, int s, final int maxT, final boolean readInside)
	{
		byte[] sector;
		while((t!=0)&&(t<tsmap.length)&&(s<tsmap[t].length)&&(!doneBefore.contains(tsmap[t][s]))&&(t<=maxT))
		{
			sector=tsmap[t][s];
			dirInfo.tracksNSecs.add(new short[]{(short)t,(short)s});
			doneBefore.add(sector);
			for(int i=2;i<256;i+=32)
			{
				if((sector[i]==(byte)128)||(sector[i]&(byte)128)==0)
					continue;

				int fn=i+19-1;
				for(;fn>=i+3;fn--)
				{
					if((sector[fn]!=-96)&&(sector[fn]!=0))
						break;
				}
				final StringBuffer file=new StringBuffer("");
				for(int x=i+3;x<=fn;x++)
					file.append((char)sector[x]);

				if(file.length()>0)
				{
					final FileInfo f = new FileInfo();
					f.parentF = dirInfo;
					finalData.add(f);

					final int pht = unsigned(sector[i+19]);
					final int phs = unsigned(sector[i+20]);
					if(((sector[i] & 0x0f)!=4) //rel files never have headers
					&&(pht!=0)
					&&(pht<=maxT)
					&&(phs<=tsmap[pht].length))
					{
						final byte[] ssec = tsmap[pht][phs];
						if((unsigned(ssec[0])==0)&&(unsigned(ssec[1])==255)&&(!doneBefore.contains(ssec)))
						{
							f.header = ssec;
							f.tracksNSecs.add(new short[]{(short)pht,(short)phs});
							doneBefore.add(ssec);
						}
					}

					if(f.header==null)
					{
						for(int ii=0;ii<file.length();ii++)
							file.setCharAt(ii, convertToPetscii((byte)file.charAt(ii)));
					}
					f.filePath=prefix + file.toString();
					f.fileName=file.toString();
					f.dirLoc=new short[]{(short)t,(short)s,(short)i};
					final short lb=unsigned(sector[i+28]);
					final short hb=unsigned(sector[i+29]);
					final int size=(256*(lb+(256*hb)));
					if(size<0)
						System.out.println(lb+","+hb+","+size);
					f.size = size;

					try
					{
						final int fileT=unsigned(sector[i+1]);
						final int fileS=unsigned(sector[i+2]);
						byte[] fileData = null;
						switch(sector[i] & 0x0f)
						{
						case (byte) 0:
							if(readInside)
							{
								fileData =getFileContent(f.fileName,tsmap,fileT,maxT,fileS,f.tracksNSecs);
								if((fileData != null)&&(fileData.length>0))
									f.size=fileData.length;
							}
							f.fileType = FileType.DEL;
							if((fileData != null)&&(fileData.length>0))
								f.size=fileData.length;
							break;
						case (byte) 1:
							if(readInside)
							{
								fileData =getFileContent(f.fileName,tsmap,fileT,maxT,fileS,f.tracksNSecs);
								if((fileData != null)&&(fileData.length>0))
									f.size=fileData.length;
							}
							f.fileType = FileType.SEQ;
							break;
						case (byte) 2:
							if(readInside)
							{
								fileData =getFileContent(f.fileName,tsmap,fileT,maxT,fileS,f.tracksNSecs);
								if((fileData != null)&&(fileData.length>0))
									f.size=fileData.length;
							}
							f.fileType = FileType.PRG;
							break;
						case (byte) 3:
							f.fileType = FileType.USR;
							//fileData =getFileContent(tsmap,fileT,maxT,fileS);
							if((unsigned(sector[i+21])==1)
							&&(f.header!=null)
							&&(fileT!=0)
							&&(!doneBefore.contains(tsmap[fileT][fileS]))&&(fileT<=maxT))
							{
								final ByteArrayOutputStream data = new ByteArrayOutputStream();
								if(readInside)
								{
									final byte[] block = new byte[254];
									final byte[] dirLocBlock =tsmap[f.dirLoc[0]][f.dirLoc[1]];
									for(int l=2;l<=31;l++)
										block[l-2]=dirLocBlock[f.dirLoc[2]+l-2];
									block[1]=0;
									block[2]=0;
									block[19]=0;
									block[20]=0;
									final byte[] convertHeader=new String("PRG formatted GEOS file V1.0").getBytes("US-ASCII");
									for(int l=30;l<30+convertHeader.length;l++)
										block[l]=convertHeader[l-30];
									data.write(block);
									for(int l=0;l<block.length;l++)
										block[l]=f.header[l+2];
									data.write(block);
								}
								final byte[] vlirSec = tsmap[fileT][fileS];
								doneBefore.add(vlirSec);
								f.tracksNSecs.add(new short[]{(short)fileT,(short)fileS});
								final byte[] vlirblock = new byte[254];
								final List<byte[]> contents = new ArrayList<byte[]>();
								for(int vt=0;vt<=254;vt+=2)
								{
									final int vfileT=unsigned(vlirSec[vt]);
									final int vfileS=unsigned(vlirSec[vt+1]);
									if((vfileT==0)&&(vfileS==255))
									{
										if(vt>1)
										{
											vlirblock[vt-2]=0;
											vlirblock[vt-1]=(byte)(255 & 0xff);
										}
										continue;
									}
									else
									if(vt==0) // this is a corrupt file.. and this is NOT a record
										continue;
									if(readInside)
									{
										final byte[] content = getFileContent(f.fileName,tsmap,vfileT,maxT,vfileS,f.tracksNSecs);
										final int numBlocks = (int)Math.round(Math.ceil(content.length/254.0));
										final int lowBlocks = (int)Math.round(Math.floor(content.length/254.0));
										final int extra = content.length - (lowBlocks * 254) +1;
										vlirblock[vt-2]=(byte)(numBlocks & 0xff);
										vlirblock[vt-1]=(byte)(extra & 0xff);
										for(int x=0;x<numBlocks*254;x+=254)
										{
											final byte[] newConBlock = new byte[254];
											for(int y=x;y<x+254;y++)
												if(y<content.length)
													newConBlock[y-x]=content[y];
												//else
												//	newConBlock[y-x]=(byte)((y-x+2) & 0xff);
											contents.add(newConBlock);
										}
									}
								}
								data.write(vlirblock); //TODO: do different for seq
								for(final byte[] content : contents)
									data.write(content);
								if(readInside)
									fileData = data.toByteArray();
							}
							else
							if(readInside)
								fileData =getFileContent(f.fileName,tsmap,fileT,maxT,fileS,f.tracksNSecs);
							break;
						case (byte) 4:
							f.fileType = FileType.REL;
							{
								final int recsz = unsigned(sector[i+21]);
								if((pht!=0)
								&&(recsz!=0)
								&&(pht<tsmap.length)
								&&(phs<tsmap[pht].length)
								&&(!doneBefore.contains(tsmap[pht][phs]))
								&&(pht<=maxT))
								{
									final byte[] sides=tsmap[pht][phs];
									doneBefore.add(sides);
									f.tracksNSecs.add(new short[]{(short)pht,(short)phs});
									if(unsigned(sides[2])==254)
									{
										if(sides[0]!=0)
											getFileContent(f.fileName,tsmap,unsigned(sides[0]),maxT,unsigned(sides[1]),f.tracksNSecs);
										for(int si=3;si<254;si+=2)
										{
											final short sit=unsigned(sides[si]);
											final short sis=unsigned(sides[si+1]);
											if(sit != 0)
											{
												if(readInside)
													getFileContent(f.fileName,tsmap,sit,maxT,sis,f.tracksNSecs);
											}
										}
									}
									else
									if(unsigned(sides[3])==recsz)
									{
										if(sides[0]!=0)
										{
											if(readInside)
												getFileContent(f.fileName,tsmap,unsigned(sides[0]),maxT,unsigned(sides[1]),f.tracksNSecs);
										}
									}
									if(readInside)
									{
										fileData =getFileContent(f.fileName,tsmap,fileT,maxT,fileS,f.tracksNSecs);
										if((fileData != null)&&(fileData.length>0))
											f.size=fileData.length;
									}
								}
							}
							break;
						case (byte) 5:
							f.fileType = FileType.CBM;
							//$FALL-THROUGH$
						case (byte) 6:
							if((sector[i] & 0x0f)==(byte)6)
								f.fileType = FileType.DIR;
							final int newDirT=fileT;
							final int newDirS=fileS;
							//if(flags.contains(COMP_FLAG.RECURSE))
							fillFileListFromHeader(imgName,srcFLen,type,f.filePath+"/",tsmap,doneBefore,finalData,newDirT,newDirS,maxT,f, readInside);
							if(readInside)
							{
								fileData=getFileContent(f.fileName,tsmap,newDirT,maxT,newDirS,f.tracksNSecs);
								if((fileData != null)&&(fileData.length>0))
									f.size=fileData.length;
							}
							//finalData.remove(f);
							break;
						default:
							f.fileType = FileType.PRG;
							if(readInside)
							{
								fileData =getFileContent(f.fileName,tsmap,fileT,maxT,fileS,f.tracksNSecs);
								if((fileData != null)&&(fileData.length>0))
									f.size=fileData.length;
							}
							break;
						}
						if(fileData==null)
						{
							errMsg(imgName+": Error reading: "+f.fileName+": "+fileT+","+fileS);
							return;
						}
						else
							f.data = fileData;
					}
					catch(final IOException e)
					{
						errMsg(imgName+": Error: "+f.filePath+": "+e.getMessage());
						return;
					}
				}
			}
			t=unsigned(sector[0]);
			s=unsigned(sector[1]);
		}
	}

	protected static boolean isSectorAllocated(final byte[][][] diskBytes, final IMAGE_TYPE imagetype, final int imageFLen, final short track, final short sector) throws IOException
	{
		final boolean[] isAllocated = new boolean[]{false};
		D64Mod.bamPeruse(diskBytes, imagetype, imageFLen, new BAMBack(){
			@Override
			public boolean call(final int t, final int s, final boolean set,
					final short[] curBAM, final short bamByteOffset,
					final short sumBamByteOffset, final short bamMask)
			{
				if((t==track)&&(s==sector))
				{
					isAllocated[0]=!set;
					return true;
				}
				return false;
			}
		});
		return isAllocated[0];
	}

	public static void fillFileListFromHeader(final String imgName, final int srcFLen, final IMAGE_TYPE type, final String prefix, final byte[][][] tsmap, final Set<byte[]> doneBefore, final List<FileInfo> finalData, int t, int s, final int maxT, final FileInfo f, final boolean readInside) throws IOException
	{
		byte[] sector;
		if((type != IMAGE_TYPE.D80)
		&&(type != IMAGE_TYPE.D82)
		&&(t!=0)
		&&(t<tsmap.length)
		&&(s<tsmap[t].length)
		&&(!doneBefore.contains(tsmap[t][s]))
		&&(t<=maxT))
		{
			sector=tsmap[t][s];
			t=unsigned(sector[0]);
			s=unsigned(sector[1]);
			final short possDTrack = unsigned(sector[160+11]);
			final short possDSector = unsigned(sector[160+12]);
			if((possDTrack!=0)
			&&(possDTrack<tsmap.length)
			&&(f.fileType==FileType.DIR)
			&&(D64Base.isSectorAllocated(tsmap, type, srcFLen, possDTrack, possDSector)
				||(possDTrack==t))
			&&(possDSector<tsmap[possDTrack].length)
			&&(!doneBefore.contains(tsmap[possDTrack][possDSector]))
			&&(possDTrack<=maxT))
			{
				finishFillFileList(f,imgName,srcFLen,type,prefix+"*/",tsmap,doneBefore,finalData,possDTrack,possDSector,maxT, readInside);
				getFileContent("/",tsmap,possDTrack,maxT,possDSector,(f!=null)?f.tracksNSecs:null); // fini
			}
		}
		finishFillFileList(f,imgName,srcFLen,type,prefix,tsmap,doneBefore,finalData,t,s,maxT, readInside);
	}

	protected static short[] getBAMStart(final IMAGE_TYPE type)
	{
		switch(type)
		{
		case D64:
			return new short[]{18,0,4,143,1,0};
		case D71:
			return new short[]{18,0,4,143,1,0};
		case D81:
			return new short[]{40,1,16,255,1,0};
		case D80:
			return new short[]{38,0,6,255,1,0};
		case D82:
			return new short[]{38,0,6,255,1,0};
		case DNP:
			return new short[]{1,2,32,255,0,32};
		}
		return null;
	}

	protected static short[] getBAMNext(final short[] prev, final IMAGE_TYPE type)
	{
		switch(type)
		{
		case D64:
			return null;
		case D71:
			if(prev[0]==18)
				return new short[]{53,0,0,104,0,3};
			return null;
		case D81:
			if((prev[0]==40)&&(prev[1]==1))
				return new short[]{40,2,16,255,1,0};
			return null;
		case D80:
			if((prev[0]==38)&&(prev[1]==0))
				return new short[]{38,3,6,140,1,5};
			return null;
		case D82:
			if((prev[0]==38)&&(prev[1]==0))
				return new short[]{38,3,6,255,1,5};
			if((prev[0]==38)&&(prev[1]==3))
				return new short[]{38,6,6,255,1,5};
			if((prev[0]==38)&&(prev[1]==6))
				return new short[]{38,9,6,255,1,5};
			return null;
		case DNP:
			if(prev[1]>=33)
				return null;
			prev[1]++;
			prev[2]=0;
			prev[3]=255;
			return prev;
		}
		return null;
	}

	public interface BAMBack
	{
		public boolean call(int t, int s, boolean set, short[] curBAM, short bamByteOffset, short sumBamByteOffset, short bamMask);
	}

	protected static void bamPeruse(final byte[][][] bytes, final IMAGE_TYPE type, final long imageSize, final BAMBack call) throws IOException
	{
		short[] currBAM = getBAMStart(type);
		byte[] bam=bytes[currBAM[0]][currBAM[1]];
		short bamOffset = currBAM[2];
		for(int t=1;t<=getImageNumTracks(type, imageSize);t++)
		{
			if(bamOffset > currBAM[3])
			{
				currBAM = getBAMNext(currBAM, type);
				if(currBAM==null)
					throw new IOException("BAM failure");
				bam=bytes[currBAM[0]][currBAM[1]];
				bamOffset = currBAM[2];
			}
			final int secsPerTrack = getImageSecsPerTrack(type,t);
			final int skipByte = currBAM[4];
			for(int s=0;s<secsPerTrack;s++)
			{
				final short sumBamByteOffset = (short)((skipByte <= 0) ? -1 : (bamOffset + skipByte));
				final short bamByteOffset = (short)(bamOffset + skipByte + (int)Math.round(Math.floor(s/8.0)));
				final short bamByte = (short)(bam[bamByteOffset] & 0xff);
				short mask;
				if(type==IMAGE_TYPE.DNP)
					mask = (short)Math.round(Math.pow(2.0,7-(s%8)));
				else
					mask = (short)Math.round(Math.pow(2.0,s%8));
				final boolean set = (bamByte & mask) == mask;
				if((call != null)&&(call.call(t, s, set, currBAM, bamByteOffset, sumBamByteOffset, mask)))
					return;
			}
			if(currBAM[5] != 0)
				bamOffset += currBAM[5];
			else
				bamOffset+=(skipByte + (int)Math.round(Math.ceil(secsPerTrack/8.0)));
		}
	}

	public static List<FileInfo> getDiskFiles(final String imgName, final IMAGE_TYPE type, final byte[][][] tsmap, final int fileSize)
	{
		int t=getImageDirTrack(type);
		final int maxT=getImageNumTracks(type, fileSize);
		int s=getImageDirSector(type);
		final List<FileInfo> finalData=new Vector<FileInfo>();
		short[] currBAM = getBAMStart(type);
		FileInfo f=new FileInfo();
		f.dirLoc=new short[]{currBAM[0],currBAM[1],currBAM[2]};
		f.fileName="*BAM*";
		f.filePath="*BAM*";
		f.fileType=FileType.DIR;
		f.size=0;
		f.tracksNSecs.add(new short[]{(short)t,(short)s});
		finalData.add(f);
		if(currBAM[1]!=0)
			f.tracksNSecs.add(new short[]{currBAM[0],(short)0});
		while(currBAM != null)
		{
			f.tracksNSecs.add(new short[]{currBAM[0],currBAM[1]});
			currBAM = getBAMNext(currBAM,type);
		}
		switch(type)
		{
		case D80:
			currBAM = getBAMStart(type);
			currBAM = getBAMNext(currBAM,type);
			// sometimes a d80 is formatted like an 8250 if user fail.
			t=39;//unsigned(tsmap[currBAM[0]][currBAM[1]][0]);
			s=1;//unsigned(tsmap[currBAM[0]][currBAM[1]][1]);
			break;
		case D82:
			currBAM = getBAMStart(type);
			currBAM = getBAMNext(currBAM,type);
			currBAM = getBAMNext(currBAM,type);
			currBAM = getBAMNext(currBAM,type);
			t=unsigned(tsmap[currBAM[0]][currBAM[1]][0]);
			s=unsigned(tsmap[currBAM[0]][currBAM[1]][1]);
			break;
		default:
			break;
		}
		final Set<byte[]> doneBefore=new HashSet<byte[]>();
		try
		{
			f=new FileInfo();
			f.dirLoc=new short[]{(short)t,(short)s,(short)0};
			f.fileName="/";
			f.filePath="/";
			f.fileType=FileType.DIR;
			f.size=0;
			finalData.add(f);
			fillFileListFromHeader(imgName, fileSize, type,"",tsmap, doneBefore, finalData, t, s, maxT,f, true);
		}
		catch(final IOException e)
		{
			errMsg(imgName+": disk Dir Error: "+e.getMessage());
		}
		switch(type)
		{
		case D71:
			for(int sec=1;sec<getImageSecsPerTrack(type, 53);sec++)
			{
				boolean found=false;
				for(final short[] chk : f.tracksNSecs)
				{
					if((chk[0]==53)&&(chk[1]==sec))
						found=true;
				}
				if(!found)
					f.tracksNSecs.add(new short[]{53,(short)sec});
			}
			break;
		default:
			break;
		}
		return finalData;
	}

	public static FileInfo getLooseFile(final File F1) throws IOException
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

	public static FileInfo getLooseFile(final InputStream fin, final String fileName, final int fileLen) throws IOException
	{
		final LOOSE_IMAGE_TYPE typ = getLooseImageTypeAndZipped(fileName);
		final byte[] filedata;
		filedata = new byte[fileLen];
		int lastLen = 0;
		while(lastLen < fileLen)
		{
			final int readBytes = fin.read(filedata, lastLen, fileLen-lastLen);
			if(readBytes < 0)
				break;
			lastLen += readBytes;
		}
		final FileInfo file = new FileInfo();
		if(typ == null)
		{
			file.fileName = fileName;
			file.fileType = D64Base.FileType.SEQ;
		}
		else
		switch(typ)
		{
		case CVT:
			file.fileName = fileName;
			file.fileType = D64Base.FileType.USR;
			break;
		case PRG:
			file.fileName = fileName.substring(0,fileName.length()-4);
			file.fileType = D64Base.FileType.PRG;
			break;
		case SEQ:
			file.fileName = fileName.substring(0,fileName.length()-4);
			file.fileType = D64Base.FileType.SEQ;
			break;
		}
		if(lastLen < fileLen && (fileLen >= MAGIC_MAX))
		{
			file.size=lastLen;
			file.data = Arrays.copyOf(filedata, lastLen);
		}
		else
		{
			file.size = fileLen;
			file.data = filedata;
		}
		return file;
	}

	protected static IMAGE_TYPE getImageType(final File F)
	{
		for(final IMAGE_TYPE img : IMAGE_TYPE.values())
		{
			if(F.getName().toUpperCase().endsWith(img.toString()))
			{
				final IMAGE_TYPE type=img;
				return type;
			}
		}
		return null;
	}

	protected static IMAGE_TYPE getImageTypeAndZipped(final String fileName)
	{
		for(final IMAGE_TYPE img : IMAGE_TYPE.values())
		{
			if(fileName.toUpperCase().endsWith(img.toString())
			||fileName.toUpperCase().endsWith(img.toString()+".GZ"))
			{
				final IMAGE_TYPE type=img;
				return type;
			}
		}
		return null;
	}

	protected static LOOSE_IMAGE_TYPE getLooseImageTypeAndZipped(final String fileName)
	{
		for(final LOOSE_IMAGE_TYPE img : LOOSE_IMAGE_TYPE.values())
		{
			final String uf = fileName.toUpperCase();
			if(uf.endsWith(img.toString())
			||uf.endsWith(img.toString()+".GZ"))
			{
				final LOOSE_IMAGE_TYPE type=img;
				return type;
			}
			else
			if(uf.endsWith(",S"))
				return LOOSE_IMAGE_TYPE.SEQ;
			else
			if(uf.endsWith(",P"))
				return LOOSE_IMAGE_TYPE.PRG;
		}
		return null;
	}

	protected static IMAGE_TYPE getImageTypeAndZipped(final File F)
	{
		if(F==null)
			return null;
		return getImageTypeAndZipped(F.getName());
	}

	protected static LOOSE_IMAGE_TYPE getLooseImageTypeAndZipped(final File F)
	{
		if(F==null)
			return null;
		return getLooseImageTypeAndZipped(F.getName());
	}
}
