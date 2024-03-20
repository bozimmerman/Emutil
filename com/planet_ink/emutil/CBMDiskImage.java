package com.planet_ink.emutil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.Vector;

import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

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
/**
 * CBM Disk Image abstraction
 * CP/M info:
 * http://commodore128.mirkosoft.sk/cpm.html
 * https://www.commodoreserver.com/BlogEntryView.asp?EID=C2D8E29076DE432CAAEBA41E7D116ECB
 * https://www.seasip.info/Cpm/format22.html
 * @author BZ
 *
 */
public class CBMDiskImage extends D64Base
{
	private final ImageType type;
	private final IOFile F;
	private int length = -1;
	private byte[][][] diskBytes = null;

	public CBMDiskImage(final IOFile F)
	{
		this.F=F;
		this.type = getImageTypeAndGZipped(F);
	}

	public CBMDiskImage(final File F)
	{
		this(new IOFile(F));
	}

	public ImageType getType()
	{
		return type;
	}

	public synchronized byte[][][] getDiskBytes()
	{
		if(diskBytes == null)
		{
			final int[] imageFLen=new int[1];
			diskBytes = this.getDisk(F, imageFLen);
			length = imageFLen[0];
			if(length <= 0)
				length = 174848;
		}
		return diskBytes;
	}

	public int getLength()
	{
		getDiskBytes(); // necess to calculate length
		return length;
	}

	public byte[] getFlatBytes()
	{
		final byte[][][] bytes = getDiskBytes();
		final ByteArrayOutputStream bout=new ByteArrayOutputStream();
		for(int x=0;x<bytes.length;x++)
		{
			if(bytes[x] == null)
				continue;
			for(int y=0;y<bytes.length;y++)
			{
				if(bytes[x][y] == null)
					continue;
				try
				{
					bout.write(bytes[x][y]);
				}
				catch (final IOException e)
				{
				}
			}
		}
		return bout.toByteArray();
	}

	// todo: add file masks to options
	public enum ImageType
	{
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
		T64 {
			public String toString() {
				return ".T64";
			}
		},
		LNX{
			public String toString() {
				return ".LNX";
			}
		}
	};

	public enum FileType
	{
		DEL,
		SEQ,
		PRG,
		USR,
		REL,
		CBM,
		DIR;
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

	public static class TrackSec implements Comparable<TrackSec>
	{
		short track;
		short sector;

		private static TrackSec[][] cache = new TrackSec[256][256];
		private TrackSec(final short t, final short s)
		{
			track=t;
			sector=s;
		}

		public static TrackSec valueOf(final short t, final short s)
		{
			if((t<0)||(t>255)||(s<0)||(s>255))
				throw new IllegalArgumentException();
			synchronized(cache)
			{
				if(cache[t][s] == null)
					cache[t][s] = new TrackSec(t,s);
				return cache[t][s];
			}
		}

		@Override
		public int compareTo(final TrackSec o)
		{
			if(track > o.track)
				return 1;
			if(track < o.track)
				return -1;
			if(sector > o.sector)
				return 1;
			if(sector < o.sector)
				return -1;
			return 0;
		}

	}

	public static class FileInfo
	{
		FileInfo		parentF			= null;
		String			fileName		= "";
		byte[]			rawFileName		= new byte[0];
		String			filePath		= "";
		FileType		fileType		= null;
		int				feblocks		= 0;
		int				size			= 0;
		byte[]			data			= null;
		Set<Long>		rollingHashes	= null;
		Set<Long>		fixedHashes		= null;
		byte[]			header			= null;
		short[]			dirLoc			= new short[3];
		List<TrackSec>	tracksNSecs		= new ArrayList<TrackSec>();
		private long	hash			= -1;

		public String toString()
		{
			return filePath;
		}

		public void reset()
		{
			hash=-1;
			rollingHashes = null;
			fixedHashes = null;
		}

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
		Set<Long> getRollingHashes()
		{
			if(rollingHashes == null)
			{
				if(size == 0 || data == null)
					rollingHashes = new TreeSet<Long>();
				else
				{
					final TreeSet<Long> rh = new TreeSet<Long>();
					long roller=0;
					for(int i=0;i<data.length;i++)
					{
						roller = (roller << 8) | data[i];
						rh.add(Long.valueOf(roller));
					}
					rollingHashes = rh;
				}
			}
			return rollingHashes;
		}
		Set<Long> getFixedHashes()
		{
			if(fixedHashes == null)
			{
				if(size == 0)
					fixedHashes = new TreeSet<Long>();
				else
				{
					final TreeSet<Long> rh = new TreeSet<Long>();
					for(int i=0;i<data.length-7;i+=8)
					{
						long roller=0;
						for(int ii=0;ii<8;ii++)
							roller = (roller << 8) | data[i+ii];
						rh.add(Long.valueOf(roller));
					}
					if((data.length>8)&&(data.length%8!=0))
					{
						long roller=0;

						for(int ii=(data.length>8?data.length-8:0);ii<data.length;ii++)
							roller = (roller << 8) | data[ii];
						rh.add(Long.valueOf(roller));
					}
					fixedHashes = rh;
				}
			}
			return fixedHashes;
		}
		public static int hashCompare(final FileInfo F1, final FileInfo F2)
		{
			try
			{
				final Set<Long> f1ch = F1.getFixedHashes();
				final Set<Long> f2ch = F2.getFixedHashes();
				final Set<Long> f1ro = F1.getRollingHashes();
				final Set<Long> f2ro = F2.getRollingHashes();
				final double lenPct = (F1.size > F2.size) ? ((double)F2.size/(double)F1.size): ((double)F1.size/(double)F2.size);
				int ct1 = 0;
				int ct2 = 0;
				for(final Long I : f1ch)
					if(f2ro.contains(I))
						ct1++;
				for(final Long I : f2ch)
					if(f1ro.contains(I))
						ct2++;
				double pct1=0;
				if(f1ch.size()>0)
					pct1=(double)ct1/(double)f1ch.size();
				double pct2=0;
				if(f2ch.size()>0)
					pct2=(double)ct2/(double)f2ch.size();
				return (int)Math.round((lenPct*pct1*pct2*30)+(pct1*35)+(pct2*35));
			}
			catch(final NullPointerException ne)
			{
				return 0;
			}
		}
	}

	private static ImageType getImageType(String fileName)
	{
		fileName = fileName.toUpperCase();
		for(final ImageType img : ImageType.values())
		{
			if(fileName.endsWith(img.toString()))
			{
				final ImageType type=img;
				return type;
			}
		}
		return null;
	}

	public static ImageType getImageTypeAndGZipped(String fileName)
	{
		fileName = fileName.toUpperCase();
		if(fileName.endsWith(".GZ"))
			return getImageType(fileName.substring(0,fileName.length()-3));
		return getImageType(fileName);
	}

	public static ImageType getImageTypeAndGZipped(final File F)
	{
		if(F==null)
			return null;
		return getImageTypeAndGZipped(F.getName());
	}

	public static ImageType getImageTypeAndGZipped(final IOFile F)
	{
		if(F==null)
			return null;
		return getImageTypeAndGZipped(F.getName());
	}

	private int getImageSecsPerTrack(final int t)
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
		case T64:
		case LNX:
			return 1;
		}
		return -1;
	}

	private int getImageTotalBytes(final int fileSize)
	{
		if((type == ImageType.T64)
		||(type == ImageType.LNX))
			return fileSize;
		final int ts=getImageNumTracks(fileSize);
		int total=0;
		for(int t=1;t<=ts;t++)
			total+=(256*getImageSecsPerTrack(t));
		return total;
	}

	private int getImageDirTrack()
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
		case T64:
		case LNX:
			return -1;
		}
		return -1;
	}

	private short getImageInterleave()
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
		case T64:
		case LNX:
			return -1;
		}
		return -1;
	}

	private int getImageDirSector()
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
		case T64:
		case LNX:
			return -1;
		}
		return -1;
	}

	private int getImageNumTracks(final long fileSize)
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
		case T64:
		case LNX:
			return 1;
		}
		return -1;
	}

	private byte[][][] parseImageDiskMap(final byte[] buf, final int fileLength)
	{
		final int numTS=getImageNumTracks(fileLength);
		final byte[][][] tsmap=new byte[numTS+1][getImageSecsPerTrack(1)][256];
		int index=0;
		for(int t=1;t<=numTS;t++)
		{
			final int secs=getImageSecsPerTrack(t);
			for(int s=0;s<secs;s++)
			{
				for(int i=0;i<256;i++)
					tsmap[t][s][i]=buf[index+i];
				index+=256;
			}
		}
		return tsmap;
	}

	private byte[][][] getDisk(final IOFile F, final int[] fileLen)
	{
		InputStream fi=null;
		try
		{
			fi=F.createInputStream();
			return getDisk(fi, F.getName(), (int)F.length(), fileLen);
		}
		catch(final IOException e)
		{
			e.printStackTrace(System.err);
			return new byte[getImageNumTracks((int)F.length())][255][256];
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

	private byte[][][] getDisk(final InputStream fin, final String fileName, final int fLen, final int[] fileLen)
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
				errMsg("?: Error: Failed to read '"+fileName+"' at ALL!");
				return new byte[getImageNumTracks(len)][255][256];
			}
			final byte[] buf=new byte[getImageTotalBytes(len)];
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
			if((type == ImageType.T64)
			||(type == ImageType.LNX))
			{
				final byte[][][] image = new byte[1][1][];
				image[0][0]=buf;
				return image;
			}
			return parseImageDiskMap(buf,len);
		}
		catch(final java.io.IOException e)
		{
			e.printStackTrace(System.err);
			return new byte[getImageNumTracks(len)][255][256];
		}
	}

	private byte[] getFileContent(final String fileName, int t, final int mt, int s, final List<TrackSec> secsUsed) throws IOException
	{
		final byte[][][] tsmap = getDiskBytes();
		final HashSet<byte[]> doneBefore=new HashSet<byte[]>();
		byte[] sector=null;
		final ByteArrayOutputStream out=new ByteArrayOutputStream();
		if(t>=tsmap.length)
			throw new IOException("Illegal File First Track "+t+" for "+fileName);
		if(s>=tsmap[t].length)
			throw new IOException("Illegal File First Sector ("+t+","+s+")"+" for "+fileName);
		while((t!=0)&&(!doneBefore.contains(tsmap[t][s]))&&(t<=mt))
		{
			if(secsUsed != null)
				secsUsed.add(TrackSec.valueOf((short)t,(short)s));
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
				throw new IOException("Illegal File Link Track "+t);
			if(s>=tsmap[t].length)
				throw new IOException("Illegal File Link Sector ("+t+","+s+")");
		}
		return out.toByteArray();
	}

	private void finishFillFileList(final FileInfo dirInfo, final String imgName,
									final String prefix, final Set<byte[]> doneBefore,
									final List<FileInfo> finalData, int t, int s, final int maxT,
									final BitSet parseFlags)
	{
		final byte[][][] tsmap = this.getDiskBytes();
		final boolean readInside = parseFlags.get(PF_READINSIDE);
		byte[] sector;
		while((t!=0)&&(t<tsmap.length)&&(s<tsmap[t].length)&&(!doneBefore.contains(tsmap[t][s]))&&(t<=maxT))
		{
			sector=tsmap[t][s];
			dirInfo.tracksNSecs.add(TrackSec.valueOf((short)t,(short)s));
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
				final byte[] rawFileName = new byte[fn-i-3+1];
				for(int x=i+3;x<=fn;x++)
					rawFileName[x-i-3] = sector[x];

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
							f.tracksNSecs.add(TrackSec.valueOf((short)pht,(short)phs));
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
					f.rawFileName = rawFileName;
					f.dirLoc=new short[]{(short)t,(short)s,(short)i};
					final short lb=unsigned(sector[i+28]);
					final short hb=unsigned(sector[i+29]);
					final int size=(256*(lb+(256*hb)));
					if(size<0)
						System.out.println(lb+","+hb+","+size);
					f.feblocks = (lb+(256*hb));
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
								fileData =getFileContent(f.fileName,fileT,maxT,fileS,f.tracksNSecs);
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
								fileData =getFileContent(f.fileName,fileT,maxT,fileS,f.tracksNSecs);
								if((fileData != null)&&(fileData.length>0))
									f.size=fileData.length;
							}
							f.fileType = FileType.SEQ;
							break;
						case (byte) 2:
							if(readInside)
							{
								fileData =getFileContent(f.fileName,fileT,maxT,fileS,f.tracksNSecs);
								if((fileData != null)&&(fileData.length>0))
									f.size=fileData.length;
							}
							f.fileType = FileType.PRG;
							break;
						case (byte) 3:
							f.fileType = FileType.USR;
							//fileData =getFileContent(tsmap,fileT,maxT,fileS);
							if((f.header!=null)
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
								int extra=0;
								if(unsigned(sector[i+21])==1)
								{
									final byte[] vlirSec = tsmap[fileT][fileS];
									doneBefore.add(vlirSec);
									f.tracksNSecs.add(TrackSec.valueOf((short)fileT,(short)fileS));
									final byte[] vlirblock = new byte[254];
									final List<byte[]> contents = new ArrayList<byte[]>();
									for(int vt=0;vt<=254;vt+=2)
									{
										final int vfileT=unsigned(vlirSec[vt]);
										final int vfileS=unsigned(vlirSec[vt+1]);
										if(vfileT==0)
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
											final byte[] content = getFileContent(f.fileName,vfileT,maxT,vfileS,f.tracksNSecs);
											final int numBlocks = (int)Math.round(Math.ceil(content.length/254.0));
											final int lowBlocks = (int)Math.round(Math.floor(content.length/254.0));
											extra = content.length - (lowBlocks * 254) +1;
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
									if((contents.size()>0)&&(extra>1)) // only on last file in the whole file
									{
										final byte[] lblk = contents.get(contents.size()-1);
										if(lblk.length>extra)
											contents.set(contents.size()-1, Arrays.copyOf(lblk, extra-1));
									}
									data.write(vlirblock);
									for(final byte[] content : contents)
										data.write(content);
								}
								else
								if(readInside)
								{
									fileData =getFileContent(f.fileName,fileT,maxT,fileS,f.tracksNSecs);
									if((fileData != null)&&(fileData.length>0))
										data.write(fileData);
								}
								if(readInside)
								{
									fileData = data.toByteArray();
									f.size=fileData.length;
								}
							}
							else
							if(readInside)
								fileData =getFileContent(f.fileName,fileT,maxT,fileS,f.tracksNSecs);
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
									f.tracksNSecs.add(TrackSec.valueOf((short)pht,(short)phs));
									if(unsigned(sides[2])==254)
									{
										if(sides[0]!=0)
											getFileContent(f.fileName,unsigned(sides[0]),maxT,unsigned(sides[1]),f.tracksNSecs);
										for(int si=3;si<254;si+=2)
										{
											final short sit=unsigned(sides[si]);
											final short sis=unsigned(sides[si+1]);
											if(sit != 0)
											{
												if(readInside)
													getFileContent(f.fileName,sit,maxT,sis,f.tracksNSecs);
											}
										}
									}
									else
									if(unsigned(sides[3])==recsz)
									{
										if(sides[0]!=0)
										{
											if(readInside)
												getFileContent(f.fileName,unsigned(sides[0]),maxT,unsigned(sides[1]),f.tracksNSecs);
										}
									}
									if(readInside)
									{
										fileData =getFileContent(f.fileName,fileT,maxT,fileS,f.tracksNSecs);
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
							fillFileListFromHeader(imgName,f.filePath+"/",doneBefore,finalData,newDirT,newDirS,maxT,f, parseFlags);
							if(readInside)
							{
								fileData=getFileContent(f.fileName,newDirT,maxT,newDirS,f.tracksNSecs);
								if((fileData != null)&&(fileData.length>0))
									f.size=fileData.length;
							}
							//finalData.remove(f);
							break;
						default:
							f.fileType = FileType.PRG;
							if(readInside)
							{
								fileData =getFileContent(f.fileName,fileT,maxT,fileS,f.tracksNSecs);
								if((fileData != null)&&(fileData.length>0))
									f.size=fileData.length;
							}
							break;
						}
						if(fileData==null)
						{
							if(!parseFlags.get(PF_NOERRORS))
								errMsg(imgName+": Error reading: "+f.fileName+": "+fileT+","+fileS);
							return;
						}
						else
							f.data = fileData;
					}
					catch(final IOException e)
					{
						if(!parseFlags.get(PF_NOERRORS))
							errMsg(imgName+": Error: "+f.filePath+": "+e.getMessage());
						//return; // omg, this doesn't mean EVERY file is bad!
					}
				}
			}
			t=unsigned(sector[0]);
			s=unsigned(sector[1]);
		}
	}

	private boolean isSectorAllocated(final short track, final short sector) throws IOException
	{
		final boolean[] isAllocated = new boolean[]{false};
		bamPeruse(new BAMBack()
		{
			@Override
			public boolean call(final short t, final short s, final boolean set,
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

	private void fillFileListFromHeader(final String imgName,final String prefix,
										final Set<byte[]> doneBefore, final List<FileInfo> finalData,
										int t, int s, final int maxT, final FileInfo f,
										final BitSet parseFlags) throws IOException
	{
		final byte[][][] tsmap = this.getDiskBytes();
		byte[] sector;
		if((type != ImageType.D80)
		&&(type != ImageType.D82)
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
			&&(isSectorAllocated(possDTrack, possDSector)
				||(possDTrack==t))
			&&(possDSector<tsmap[possDTrack].length)
			&&(!doneBefore.contains(tsmap[possDTrack][possDSector]))
			&&(possDTrack<=maxT))
			{
				finishFillFileList(f,imgName,prefix+"*/",doneBefore,finalData,possDTrack,possDSector,maxT, parseFlags);
				getFileContent("/",possDTrack,maxT,possDSector,(f!=null)?f.tracksNSecs:null); // fini
			}
		}
		finishFillFileList(f,imgName,prefix,doneBefore,finalData,t,s,maxT, parseFlags);
	}

	private short[] getBAMStart()
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
		case LNX:
		case T64:
			return null;
		}
		return null;
	}

	private short[] getBAMNext(final short[] prev)
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
		case LNX:
		case T64:
			return null;
		}
		return null;
	}

	public interface BAMBack
	{
		public boolean call(short t, short s, boolean set, short[] curBAM, short bamByteOffset, short sumBamByteOffset, short bamMask);
	}

	public void bamPeruse(final BAMBack call) throws IOException
	{
		if((type == ImageType.T64)
		||(type == ImageType.LNX))
			throw new IOException("Illegal image type.");
		final byte[][][] bytes=getDiskBytes();
		final long imageSize=getLength();
		short[] currBAM = getBAMStart();
		byte[] bam=bytes[currBAM[0]][currBAM[1]];
		short bamOffset = currBAM[2];
		for(int t=1;t<=getImageNumTracks(imageSize);t++)
		{
			if(bamOffset > currBAM[3])
			{
				currBAM = getBAMNext(currBAM);
				if(currBAM==null)
					throw new IOException("BAM failure");
				bam=bytes[currBAM[0]][currBAM[1]];
				bamOffset = currBAM[2];
			}
			final int secsPerTrack = getImageSecsPerTrack(t);
			final int skipByte = currBAM[4];
			for(int s=0;s<secsPerTrack;s++)
			{
				final short sumBamByteOffset = (short)((skipByte <= 0) ? -1 : (bamOffset + skipByte));
				final short bamByteOffset = (short)(bamOffset + skipByte + (int)Math.round(Math.floor(s/8.0)));
				final short bamByte = (short)(bam[bamByteOffset] & 0xff);
				short mask;
				if(type==ImageType.DNP)
					mask = (short)Math.round(Math.pow(2.0,7-(s%8)));
				else
					mask = (short)Math.round(Math.pow(2.0,s%8));
				final boolean set = (bamByte & mask) == mask;
				if((call != null)&&(call.call((short)t, (short)s, set, currBAM, bamByteOffset, sumBamByteOffset, mask)))
					return;
			}
			if(currBAM[5] != 0)
				bamOffset += currBAM[5];
			else
				bamOffset+=(skipByte + (int)Math.round(Math.ceil(secsPerTrack/8.0)));
		}
	}

	private List<FileInfo> getTapeFiles(final String imgName, final BitSet parseFlags)
	{
		final byte[][][] tsmap = this.getDiskBytes();
		final int fileSize = getLength();
		final List<FileInfo> finalData=new Vector<FileInfo>();
		final byte[] data = tsmap[0][0];
		final String marker = new String(data,0,32);
		if(!marker.startsWith("C64"))
		{
			if(!parseFlags.get(PF_NOERRORS))
				errMsg(imgName+": tape header error.");
			return finalData;
		}
		//final int tapeVersionNumber = (data[32] & 0xff) + (data[33] & 0xff);
		final int numEntries = (data[34] & 0xff) + (256 * (data[35] & 0xff));
		//final int usedEntries = (data[36] & 0xff)+ (256 * (data[37] & 0xff)); // might be gaps, so dont use this!
		// we skip the tape/disk name. :(

		// first must do a pre-scan, because some T64s are Wrong.
		final TreeSet<Integer> fileStarts = new TreeSet<Integer>();
		for(int start = 64; start<64+(32*numEntries); start+=32)
		{
			if(data[start]!=1)
				continue;
			final int startAddress = (data[start+2] & 0xff) + (256*(data[start+3] & 0xff));
			fileStarts.add(Integer.valueOf(startAddress));
		}

		for(int start = 64; start<64+(32*numEntries); start+=32)
		{
			if(data[start]!=1)
				continue;
			final int startAddress = (data[start+2] & 0xff) + (256*(data[start+3] & 0xff));
			int endAddress = (data[start+4]& 0xff) + (256*(data[start+5]& 0xff));
			if(endAddress == 50118) // this means it's wrong.
			{
				for(final Iterator<Integer> i = fileStarts.iterator();i.hasNext();)
				{
					final Integer startI = i.next();
					if(startI.intValue() == startAddress)
					{
						if(i.hasNext())
							endAddress = i.next().intValue()-1;
						else
							endAddress = fileSize-1;
						break;
					}
				}
			}
			final int dataOffset = (data[start+8] & 0xff)
									+ (256*(data[start+9] & 0xff))
									+ (65536 * (data[start+10] & 0xff)); // nothing higher is Good.
			final int fileLength = endAddress-startAddress+1;
			final FileInfo f = new FileInfo();
			finalData.add(f);
			f.header = Arrays.copyOfRange(data, start, start+32);
			final StringBuffer file=new StringBuffer("");
			int fn=start+32-1;
			for(;fn>=start+16;fn--)
			{
				if((data[fn]!=-96)
				&&(data[fn]!=0)
				&&(data[fn]!=32))
					break;
			}
			final byte[] rawFilename = new byte[fn-start-16+1];
			for(int x=start+16;x<=fn;x++)
			{
				file.append((char)data[x]);
				rawFilename[x-start-16] = data[x];
			}
			for(int ii=0;ii<file.length();ii++)
				file.setCharAt(ii, convertToPetscii((byte)file.charAt(ii))); // this makes no sense to me
			f.filePath="/" + file.toString();
			f.fileName=file.toString();
			f.rawFileName = rawFilename;
			f.size=fileLength;
			{
				final byte[] bytes = numToBytes(start);
				f.dirLoc=new short[] {(short)(bytes[0]&0xff),(short)(bytes[1]&0xff),(short)(bytes[2]&0xff)};
			}
			f.feblocks = (int)Math.round(Math.floor(f.size / 254));
			if(data[start+1]!=0) // this is mega lame -- what a messed up format
				f.fileType = FileType.PRG;
			else
				f.fileType = FileType.SEQ;
			final int fakeStart = (f.fileType == FileType.PRG)?dataOffset-2:dataOffset;
			if(fakeStart + f.size + 1 > fileSize)
			{
				if(!parseFlags.get(PF_NOERRORS))
					errMsg(imgName+": has bad offsets for file "+f.filePath+", trimming it.");
				f.data = Arrays.copyOfRange(data, fakeStart, fileSize);
			}
			else
				f.data = Arrays.copyOfRange(data, fakeStart, fakeStart+f.size+1);
			if(f.fileType == FileType.PRG)
			{
				f.data[0] = data[start+2];
				f.data[1] = data[start+3];
			}
		}
		return finalData;
	}

	public List<FileInfo> getFiles(final BitSet parseFlags)
	{
		final String imgName = F.getName();
		final byte[][][] tsmap = this.getDiskBytes();
		final int fileSize = getLength();
		if(type == ImageType.T64)
			return getTapeFiles(imgName,parseFlags);
		else
		if(type == ImageType.LNX)
		{
			try
			{
				return this.getLNXDeepContents(imgName,parseFlags);
			}
			catch(final IOException e)
			{
				errMsg(e.getMessage());
				return new ArrayList<FileInfo>();
			}
		}
		int t=getImageDirTrack();
		final int maxT=getImageNumTracks( fileSize);
		int s=getImageDirSector();
		final List<FileInfo> finalData=new Vector<FileInfo>();
		short[] currBAM = getBAMStart();
		FileInfo f=new FileInfo();
		f.dirLoc=new short[]{currBAM[0],currBAM[1],currBAM[2]};
		f.fileName="*BAM*";
		f.filePath="*BAM*";
		f.fileType=FileType.DIR;
		f.size=0;
		f.feblocks=0;
		f.tracksNSecs.add(TrackSec.valueOf((short)t,(short)s));
		finalData.add(f);
		if(currBAM[1]!=0)
			f.tracksNSecs.add(TrackSec.valueOf(currBAM[0],(short)0));
		while(currBAM != null)
		{
			f.tracksNSecs.add(TrackSec.valueOf(currBAM[0],currBAM[1]));
			currBAM = getBAMNext(currBAM);
		}
		switch(type)
		{
		case D80:
			currBAM = getBAMStart();
			currBAM = getBAMNext(currBAM);
			// sometimes a d80 is formatted like an 8250 if user fail.
			t=39;//unsigned(tsmap[currBAM[0]][currBAM[1]][0]);
			s=1;//unsigned(tsmap[currBAM[0]][currBAM[1]][1]);
			break;
		case D82:
			currBAM = getBAMStart();
			currBAM = getBAMNext(currBAM);
			currBAM = getBAMNext(currBAM);
			currBAM = getBAMNext(currBAM);
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
			f.feblocks=0;
			finalData.add(f);
			parseFlags.set(PF_READINSIDE);
			fillFileListFromHeader(imgName, "", doneBefore, finalData, t, s, maxT,f, parseFlags);
		}
		catch(final IOException e)
		{
			if(!parseFlags.get(PF_NOERRORS))
				errMsg(imgName+": disk Dir Error: "+e.getMessage());
		}
		switch(type)
		{
		case D71:
			for(int sec=1;sec<getImageSecsPerTrack(53);sec++)
			{
				boolean found=false;
				for(final TrackSec chk : f.tracksNSecs)
				{
					if((chk.track==53)&&(chk.sector==sec))
						found=true;
				}
				if(!found)
					f.tracksNSecs.add(TrackSec.valueOf((short)53,(short)sec));
			}
			break;
		default:
			break;
		}
		return finalData;
	}

	public static void normalizeCvt(final FileInfo file)
	{
		if((file.data != null)
		&&(file.data.length>256)
		&&(new String(file.data,34,24).equals("formatted GEOS file V1.0")))
		{
			file.data[1]=0;
			file.data[2]=0;
			file.data[19]=0;
			file.data[20]=0;
			file.data[30]='P';
			file.data[31]='R';
			file.data[32]='G';
			for(int i=60;i<254;i++)
				file.data[i]=0;
		}
	}

	private static String getNextLYNXLineFromInputStream(final InputStream in, final int[] bytesRead, final OutputStream bout) throws IOException
	{
		final StringBuilder line=new StringBuilder("");
		while(in.available()>0)
		{
			int b=in.read();
			if(b<0)
				throw new IOException("Unexpected EOF");
			if(bytesRead != null)
				bytesRead[0]++;
			if(b==13)
			{
				if(line.length()>0)
					break;
			}
			if(bout != null)
				bout.write(b);
			if(b == 160)
				continue;
			b = D64Base.convertToAscii(b);
			if(b != 0)
				line.append((char)b);
		}
		return line.toString();
	}

	private List<FileInfo> getLNXDeepContents(final String imgName, final BitSet parseFlags) throws IOException
	{
		final List<FileInfo> list = new ArrayList<FileInfo>();
		final byte[] data = getDiskBytes()[0][0];
		final InputStream in = new ByteArrayInputStream(data);
		final int[] bytesSoFar=new int[1];
		int zeroes=0;
		while(in.available()>0)
		{
			final int b = in.read();
			if(b<0)
				break;
			bytesSoFar[0]++;
			if(b == 0)
			{
				zeroes++;
				if(zeroes==3)
					break;
			}
			else
				zeroes=0;
		}
		if(zeroes != 3)
			throw new IOException("Illegal LNX format: missing 0 0 0");
		final String sigLine = getNextLYNXLineFromInputStream(in, bytesSoFar, null).toUpperCase().trim();
		int headerSize = 0;
		final String[] splitSig = sigLine.split(" ");
		final String sz = splitSig[0].trim();
		if((sz.length()>0)
		&&(Character.isDigit(sz.charAt(0)))
		&&(sz.length()<20))
			headerSize = Integer.parseInt(sz);
		if((sigLine.length()==0)
		||(headerSize <= 0)
		||(sigLine.indexOf("LYNX")<0))
			throw new IOException("Illegal signature: "+sigLine);
		final int numEntries;
		if(isInteger(splitSig[splitSig.length-1])
		&&(Integer.parseInt(splitSig[splitSig.length-1])<1000))
		{
			numEntries = Integer.parseInt(splitSig[splitSig.length-1]);
		}
		else
		{
			final String numEntryLine = getNextLYNXLineFromInputStream(in, bytesSoFar, null).toUpperCase().trim();
			if((numEntryLine.length()==0)
			||(!Character.isDigit(numEntryLine.charAt(0))))
				throw new IOException("Illegal numEntries: "+numEntryLine);
			numEntries = Integer.parseInt(numEntryLine);
		}
		final byte[] rawDirBlock = new byte[(254 - bytesSoFar[0]) + ((headerSize-1) * 254)];
		int bytesRead = 0;
		while((in.available()>0) && (bytesRead < rawDirBlock.length))
		{
			final int justRead = in.read(rawDirBlock, bytesRead, rawDirBlock.length-bytesRead);
			if(justRead < 0)
				break;
			if(justRead > 0)
				bytesRead += justRead;
		}
		if(bytesRead < rawDirBlock.length)
			throw new IOException("Incomplete Directory Block");
		final ByteArrayInputStream bin=new ByteArrayInputStream(rawDirBlock);
		for(int i=0;i<numEntries;i++)
		{
			final ByteArrayOutputStream fout = new ByteArrayOutputStream();
			final String fileName =  getNextLYNXLineFromInputStream(bin, null, fout); // don't trim, cuz spaces are valid.
			final String numBlockSz= getNextLYNXLineFromInputStream(bin, null, null).toUpperCase().trim();
			final String typChar =   getNextLYNXLineFromInputStream(bin, null, null).toUpperCase().trim();
			String lastBlockSz=getNextLYNXLineFromInputStream(bin, null, null).toUpperCase().trim();
			if((lastBlockSz.length()==0)||(!Character.isDigit(lastBlockSz.charAt(0))))
				lastBlockSz="0";

			if((fileName.length()==0)
			||(numBlockSz.length()==0)||(!Character.isDigit(numBlockSz.charAt(0)))
			||(typChar.length()==0)||(typChar.length()>3)
			||(lastBlockSz.length()==0)||(!Character.isDigit(lastBlockSz.charAt(0))))
				throw new IOException("Bad directory entry "+(i+1)+": "+fileName+"."+typChar+": "+numBlockSz+"("+lastBlockSz+")");
			final FileInfo file = new FileInfo();
			file.fileName = fileName;
			file.rawFileName = fout.toByteArray();
			file.fileType = FileType.fileType(typChar);
			file.size = ((Integer.valueOf(numBlockSz).intValue()-1) * 254) + Integer.valueOf(lastBlockSz).intValue()-1;
			list.add(file);
		}
		for(final FileInfo f : list)
		{
			int fbytesRead = 0;
			int numBlocks = (int)Math.round(Math.floor(f.size / 254.0));
			if((f.size % 254) > 0)
				numBlocks++;
			final int allBlocksSize = numBlocks * 254;
			final byte[] fileSubBytes = new byte[allBlocksSize];
			while((in.available()>0) && (fbytesRead < allBlocksSize))
			{
				final int justRead = in.read(fileSubBytes, fbytesRead, allBlocksSize-fbytesRead);
				if(justRead < 0)
					break;
				if(justRead > 0)
					fbytesRead += justRead;
			}
			if(fbytesRead < allBlocksSize)
			{
				if((list.get(list.size()-1)!=f)
				||(fbytesRead < f.size-1024))
				{
					System.err.println("Incomplete data for "+f.fileName+" in LYNX file.");
					if(f == list.get(0))
						throw new IOException("Incomplete data for "+f.fileName);
					return list;
				}
			}
			f.data = Arrays.copyOf(fileSubBytes, f.size);
		}
		return list;
	}

	private short[] findFreeSector(final short startTrack, final short stopTrack, final short dir, final Set<Integer> skip) throws IOException
	{
		short track=startTrack;
		short numFree=0;
		while(track!=stopTrack)
		{
			numFree=sectorsFreeOnTrack( track);
			if(numFree > 0)
			{
				final short interleave=getImageInterleave();
				final int secsOnTrack=getImageSecsPerTrack(track);
				for(short s1=0;s1<interleave;s1++)
					for(short s=s1;s<secsOnTrack;s+=interleave)
					{
						final Integer sint = Integer.valueOf((track<<8)+s);
						if((skip==null)||(!skip.contains(sint)))
						{
							if(!isSectorAllocated(track,s))
								return new short[]{track,s};
						}
					}
			}
			track+=dir;
		}
		return null;
	}

	private short[] nextFreeSectorInDirection(final short dirTrack, final short startTrack, final short lastTrack, final Set<Integer> skip) throws IOException
	{
		if(startTrack == dirTrack)
		{
			final short[] tryBelow = nextFreeSectorInDirection(dirTrack,(short)(dirTrack-1),lastTrack,skip);
			final short[] tryAbove = nextFreeSectorInDirection(dirTrack,(short)(dirTrack+1),lastTrack,skip);
			if((tryAbove==null)&&(tryBelow==null))
				return null;
			if((tryAbove==null)&&(tryBelow != null))
				return tryBelow;
			if((tryBelow==null)&&(tryAbove != null))
				return tryAbove;
			final int diffBelow = dirTrack-tryBelow[0];
			final int diffAbove = tryAbove[0]-dirTrack;
			if(diffBelow>diffAbove)
				return tryAbove;
			else
				return tryBelow;
		}
		else
		{
			short dir=1;
			short stopTrack=(short)(lastTrack+1);
			if(startTrack<dirTrack)
			{
				dir=-1;
				stopTrack=0;
			}
			return findFreeSector((short)(dirTrack+dir),stopTrack,dir,skip);
		}
	}

	private short[] firstFreeSector(final Set<Integer> skip) throws IOException
	{
		final int imageFLen = getLength();
		final short dirTrack=(short)getImageDirTrack();
		final short lastTrack=(short)getImageNumTracks(imageFLen);
		if(type == ImageType.D71)
		{
			short[] sector = nextFreeSectorInDirection(dirTrack,dirTrack,(short)35,skip);
			if(sector == null)
				sector = nextFreeSectorInDirection((short)53,(short)53,lastTrack,skip);
			return sector;
		}
		else
		if(type == ImageType.D82)
		{
			short[] sector = nextFreeSectorInDirection(dirTrack,dirTrack,(short)77,skip);
			if(sector == null)
				sector = nextFreeSectorInDirection((short)116,(short)116,lastTrack,skip);
			return sector;
		}
		else
		{
			return nextFreeSectorInDirection(dirTrack,dirTrack,lastTrack,skip);
		}
	}

	public List<short[]> getFreeSectors(final int numSectors) throws IOException
	{
		if((type == ImageType.LNX)
		||(type == ImageType.T64))
			throw new IOException("Illegal image type.");
		final int imageFLen = getLength();
		final List<short[]> list=new ArrayList<short[]>();
		if(totalSectorsFree()<numSectors)
			throw new IOException("Not enough free space.");
		short[] ts=null;
		final short lastTrack=(short)getImageNumTracks(imageFLen);
		final short dirTrack=(short)getImageDirTrack();
		final HashSet<Integer> skip=new HashSet<Integer>();
		while(list.size()<numSectors)
		{
			if(ts != null)
			{
				final short prevTrack=ts[0];
				if(type == ImageType.D71)
				{
					if(prevTrack<=35)
						ts = nextFreeSectorInDirection(dirTrack,prevTrack,(short)35,skip);
					else
						ts = nextFreeSectorInDirection((short)53,prevTrack,lastTrack,skip);
					if((ts == null)&&(prevTrack<=35))
						ts = nextFreeSectorInDirection((short)53,(short)53,lastTrack,skip);
				}
				else
				if(type == ImageType.D82)
				{
					if(prevTrack<=35)
						ts = nextFreeSectorInDirection(dirTrack,prevTrack,(short)77,skip);
					else
						ts = nextFreeSectorInDirection((short)116,prevTrack,lastTrack,skip);
					if((ts == null)&&(prevTrack<=35))
						ts = nextFreeSectorInDirection((short)116,(short)116,lastTrack,skip);
				}
				else
					ts = nextFreeSectorInDirection(dirTrack,prevTrack,lastTrack,skip);
			}
			if(ts == null)
				ts=firstFreeSector(skip);
			if(ts != null)
			{

				final Integer sint = Integer.valueOf((ts[0]<<8)+ts[1]);
				skip.add(sint);
				list.add(ts);
			}
		}

		return list;
	}

	private short sectorsFreeOnTrack(final short track) throws IOException
	{
		final int[] numAllocated = new int[]{0};
		final boolean[] leaveWhenDone=new boolean[]{false};
		bamPeruse(new BAMBack()
		{
			@Override
			public boolean call(final short t, final short s, final boolean set,
					final short[] curBAM, final short bamByteOffset,
					final short sumBamByteOffset, final short bamMask)
			{
				if(t==track)
				{
					leaveWhenDone[0]=true;
					if(!set)
						numAllocated[0]++;
				}
				else
				if(leaveWhenDone[0])
					return true;
				return false;
			}
		});
		return (short)(getImageSecsPerTrack(track) - numAllocated[0]);
	}

	private int totalSectorsFree() throws IOException
	{
		final int[] numAllocated = new int[]{0};
		final int[] numFree = new int[]{0};
		final int noTrack = getImageDirTrack();
		bamPeruse(new BAMBack()
		{
			@Override
			public boolean call(final short t, final short s, final boolean set,
					final short[] curBAM, final short bamByteOffset,
					final short sumBamByteOffset, final short bamMask)
			{
				if(!set)
					numAllocated[0]++;
				else
				if(t != noTrack)
					numFree[0]++;
				return false;
			}
		});
		return numFree[0];
	}

	private boolean scratchTapeFile(final FileInfo file) throws IOException
	{
		final byte[][][] diskBytes = this.getDiskBytes();
		// first kill the data;
		final int dirLocOffset = file.dirLoc[0] + (256 * file.dirLoc[1]) + (65536 * file.dirLoc[2]);
		byte[] data = diskBytes[0][0];
		final int numEntries = (data[34] & 0xff) + (256 * (data[35] & 0xff));
		final int usedEntries = (data[36] & 0xff)+ (256 * (data[37] & 0xff));
		final int startAddress = (data[dirLocOffset+2] & 0xff) + (256*(data[dirLocOffset+3] & 0xff));
		byte[] start = Arrays.copyOfRange(data, 0, startAddress);
		byte[] rest = Arrays.copyOfRange(data, startAddress + file.size, data.length);
		data = Arrays.copyOf(start, start.length+rest.length);
		for(int i=0;i<rest.length;i++)
			data[start.length+i] = rest[i];
		// data killed.  now kill the dir-entry, if that is wise
		if(numEntries > 1)
		{
			start = Arrays.copyOfRange(data, 0, dirLocOffset);
			rest = Arrays.copyOfRange(data, dirLocOffset + 32, data.length);
			data = Arrays.copyOf(start, start.length+rest.length);
			for(int i=0;i<rest.length;i++)
				data[start.length+i] = rest[i];
			final byte[] numEntriesB = numToBytes(numEntries-1);
			data[34] = numEntriesB[0];
			data[35] = numEntriesB[1];
		}
		else
		{
			for(int i=0;i<32;i++) // only one left, so just clear it
				data[dirLocOffset+i]=0;
		}
		// now decrease the number of used entries, and we're done
		final byte[] usedEntriesB = numToBytes(usedEntries-1);
		data[36] = usedEntriesB[0];
		data[37] = usedEntriesB[1];
		diskBytes[0][0] = data;
		return false;
	}

	public boolean scratchFile(final FileInfo file) throws IOException
	{
		if(type == ImageType.T64)
			return scratchTapeFile(file);
		if(type == ImageType.LNX)
			return false; //TODO:
		final byte[] dirSector = diskBytes[file.dirLoc[0]][file.dirLoc[1]];
		dirSector[file.dirLoc[2]]=(byte)(0);
		final Set<Integer> tsSet=new HashSet<Integer>();
		for(final TrackSec ts : file.tracksNSecs)
			tsSet.add(Integer.valueOf((ts.track << 8) + ts.sector));

		bamPeruse(new BAMBack()
		{
			@Override
			public boolean call(final short t, final short s, final boolean set,
					final short[] curBAM, final short bamByteOffset,
					final short sumBamByteOffset, final short bamMask)
			{

				final Integer tsInt=Integer.valueOf((t << 8) + s);
				if(tsSet.contains(tsInt)&&(!set))
				{
					if(sumBamByteOffset>=0)
					{
						try
						{
							final int totalSectors=getImageSecsPerTrack(t);
							final int sectorsFree=sectorsFreeOnTrack(t);
							if((sectorsFree<=totalSectors)
							&&(((curBAM[sumBamByteOffset]&0xff)==sectorsFree)))
								curBAM[sumBamByteOffset] = (byte)((curBAM[sumBamByteOffset]&0xff)-1);
						}
						catch(final Exception e)
						{
						}
					}
					final byte[] bamSector = diskBytes[curBAM[0]][curBAM[1]];
					bamSector[bamByteOffset] = (byte)(bamSector[bamByteOffset] | bamMask);
				}
				return false;
			}
		});
		return true;
	}

	private static short[] findDirectorySlotInSector(final byte[] sector, final TrackSec dirts) throws IOException
	{
		for(int i=2;i<256;i+=32)
		{
			if((sector[i]==(byte)128)||(sector[i]&(byte)128)==0)
				return new short[]{dirts.track,dirts.sector,(short)i};
		}
		return null;
	}

	public short[] findDirectorySlot(final FileInfo parentDir) throws IOException
	{
		if((type == ImageType.LNX)
		||(type == ImageType.T64))
			return null;
		final TrackSec dirts = parentDir.tracksNSecs.get(0);
		final byte[][][] diskBytes = this.getDiskBytes();
		final int imageFLen = getLength();
		/*
		if((parentDir.fileType != FileType.CBM)
		&&(imagetype != IMAGE_TYPE.D80)
		&&(imagetype != IMAGE_TYPE.D82)
		&&(dirts[0]!=0)
		&&(dirts[0]<diskBytes.length)
		&&(dirts[1]<diskBytes[dirts[0]].length))
		{
			byte[] sec=diskBytes[dirts[0]][dirts[1]];
			dirts[0]=unsigned(sec[0]);
			dirts[1]=unsigned(sec[1]);
		}
		*/
		byte[] sec=diskBytes[dirts.track][dirts.sector];
		short[] found=findDirectorySlotInSector(sec,dirts);
		if(found != null)
			return found;
		while(sec[0]!=0)
		{
			dirts.track=unsigned(sec[0]);
			dirts.sector=unsigned(sec[1]);
			sec=diskBytes[dirts.track][dirts.sector];
			found=findDirectorySlotInSector(sec,dirts);
			if(found != null)
				return found;
		}
		if(type != ImageType.DNP)
		{
			final short track=dirts.track;
			final int numFree=sectorsFreeOnTrack( track);
			if(numFree < 0)
				throw new IOException("No root dir sectors free?!");
			final short interleave=3;
			final int secsOnTrack=getImageSecsPerTrack(track);
			for(short s1=0;s1<interleave;s1++)
			{
				for(short s=s1;s<secsOnTrack;s+=interleave)
				{
					if(!isSectorAllocated(track,s))
					{
						sec[0]=(byte)(track & 0xff);
						sec[1]=(byte)(s & 0xff);
						final byte[] newSec = diskBytes[track][s];
						for(int i=2;i<256;i+=32)
							newSec[i]=0;
						final List<short[]> allocs=new ArrayList<short[]>();
						allocs.add(new short[]{track,s});
						allocateSectors(allocs);
						return new short[]{track,s,2};
					}
				}
			}
			throw new IOException("No free root dir sectors found.");
		}
		else
		{
			final short track=dirts.track;
			short[] ts= findFreeSector(track,(short)(getImageNumTracks(imageFLen)+1),(short)1,null);
			if(ts == null)
				ts= findFreeSector((short)1,(short)(getImageNumTracks(imageFLen)+1),(short)1,null);
			if(ts == null)
				throw new IOException("No sectors found for dir entry.");
			sec[0]=(byte)(ts[0] & 0xff);
			sec[1]=(byte)(ts[1] & 0xff);
			final byte[] newSec = diskBytes[ts[0]][ts[1]];
			for(int i=2;i<256;i+=32)
				newSec[i]=0;
			final List<short[]> allocs=new ArrayList<short[]>();
			allocs.add(new short[]{ts[0],ts[1]});
			allocateSectors(allocs);
			return new short[]{ts[0],ts[1],2};
		}
	}

	public boolean allocateSectors(final List<short[]> sectors) throws IOException
	{

		final HashSet<Integer> secsToDo=new HashSet<Integer>();
		for(final short[] sec : sectors)
			secsToDo.add(Integer.valueOf((sec[0] << 8) + sec[1]));
		bamPeruse(new BAMBack()
		{
			@Override
			public boolean call(final short t, final short s, final boolean set,
					final short[] curBAM, final short bamByteOffset,
					final short sumBamByteOffset, final short bamMask)
			{

				final Integer tsInt=Integer.valueOf((t << 8) + s);
				if(secsToDo.contains(tsInt)&&(set))
				{
					if(sumBamByteOffset>=0)
					{
						try
						{
							final int totalSectors=getImageSecsPerTrack(t);
							final int sectorsFree=sectorsFreeOnTrack(t);
							if((sectorsFree<=totalSectors)
							&&(((curBAM[sumBamByteOffset]&0xff)==sectorsFree)))
								curBAM[sumBamByteOffset] = (byte)((curBAM[sumBamByteOffset]&0xff)+1);
						}
						catch(final Exception e)
						{
						}
					}
					final byte[] bamSector = diskBytes[curBAM[0]][curBAM[1]];
					bamSector[bamByteOffset] = (byte)(bamSector[bamByteOffset] & (255-bamMask));
				}
				return false;
			}
		});
		return true;
	}
}
