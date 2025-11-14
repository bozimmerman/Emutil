package com.planet_ink.emutil;

import java.io.*;
import java.util.*;

import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import com.planet_ink.emutil.archives.*;

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
	private CPMType cpmOs = CPMType.NOT;
	private byte[][][] diskBytes = null;
	private TrackSec[][] cpmAllocUnits = null;

	public CBMDiskImage(final IOFile F)
	{
		this.F=F;
		this.type = getImageTypeAndGZipped(F);
		this.cpmOs=CPMType.NOT;
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
			this.cpmOs = CPMType.NOT;
			diskBytes = this.getDisk(F, imageFLen);
			length = imageFLen[0];
			if((type == ImageType.LNX)
			||(type == ImageType.LBR)
			||(type == ImageType.ARC)
			||(type == ImageType.SDA)
			||(type == ImageType.T64))
				return diskBytes;
			if(length <= 0)
				length = 174848;
			// now, attempt to identify CP/M disks.
			final int dt = type.dirHead.track;
			final int ds = type.dirHead.sector;
			if((dt>0)
			&&(ds>=0)
			&&((type == ImageType.D64)
				||(type == ImageType.D71)
				||(type == ImageType.D81)
				||(type == ImageType.D80)
				||(type == ImageType.D82)))
			{
				final byte[] hdr = diskBytes[dt][ds];
				final String diskName;
				final String diskId;
				if((type == ImageType.D80)
				||(type == ImageType.D82))
				{
					diskName = new String(hdr,6,5);
					diskId = new String(hdr,24,5);
				}
				else
				if(type == ImageType.D81)
				{
					diskName = new String(hdr,4,9);
					diskId = new String(hdr,22,5);
				}
				else
				{
					diskName = new String(hdr,144,9);
					diskId = new String(hdr,162,5);
				}
				int firstDirTrack = 1;
				final int numTracks = type.numTracks(length);
				final Set<TrackSec> skipThese = new TreeSet<TrackSec>();
				if(((type == ImageType.D64)||(type == ImageType.D71))
				&& diskName.equalsIgnoreCase("CP/M PLUS")
				&& diskId.startsWith("65")
				&& diskId.endsWith("2A")
				&& new String(diskBytes[1][0],0,3).equals("CBM"))
				{
					this.cpmOs = CPMType.NORMAL;
					skipThese.add(new TrackSec((short)1,(short)0));
					skipThese.add(new TrackSec((short)1,(short)5));
					skipThese.add(new TrackSec((short)18,(short)0));
					if(type == ImageType.D71)
					{
						skipThese.add(new TrackSec((short)36,(short)0));
						skipThese.add(new TrackSec((short)36,(short)5));
						skipThese.add(new TrackSec((short)53,(short)0));
					}
				}
				else
				if((type == ImageType.D64)
				&& (diskName.equalsIgnoreCase("CP/M DISK")||diskName.equalsIgnoreCase("CP/M PLUS"))
				&& diskId.startsWith("65")
				&& diskId.endsWith("2A")
				&& (!new String(diskBytes[1][0],0,3).equals("CBM")))
				{
					this.cpmOs = CPMType.C64;
					firstDirTrack = 3;
					for(int t=1;t<numTracks;t++)
					{
						final int startSec = (t==18)?0:17;
						final int numSecs = type.sectors(t, cpmOs);
						for(int s=startSec;s<numSecs;s++)
							skipThese.add(new TrackSec((short)t,(short)s));
					}
				}
				else
				if((type == ImageType.D81)
				&&diskName.equalsIgnoreCase("CP/M PLUS")
				&& diskId.startsWith("80")
				&& diskId.endsWith("3D")
				//&& new String(diskBytes[1][0],0,3).equals("CBM")
				)
				{
					this.cpmOs = CPMType.NORMAL;
					for(int s=0;s<20;s++)
						skipThese.add(new TrackSec((short)40,(short)s));
				}
				else
				if(((type == ImageType.D80)||(type == ImageType.D81))
				&&diskName.equalsIgnoreCase("CPM86")
				&& diskId.startsWith("86")
				&& diskId.endsWith("2C"))
				{
					this.cpmOs = CPMType.CBM;
					firstDirTrack = 3;
					for(int t=1;t<firstDirTrack;t++)
					{
						final int startSec = 0;
						final int numSecs = type.sectors(t, cpmOs);
						for(int s=startSec;s<numSecs;s++)
							skipThese.add(new TrackSec((short)t,(short)s));
					}
					for(int t=38;t<=39;t++)
					{
						final int startSec = 0;
						final int numSecs = type.sectors(t, cpmOs);
						for(int s=startSec;s<numSecs;s++)
							skipThese.add(new TrackSec((short)t,(short)s));
					}
				}
				final int allocUnitSize = this.getCPMAllocUnits(diskBytes);
				final List<TrackSec[]> alloBuilder = new ArrayList<TrackSec[]>();
				final int interleave = type.interleave(cpmOs);
				final short[] ts = new short[] { (short)firstDirTrack, (short)0};
				short totalSecs = type.sectors(ts[0], cpmOs);
				while(ts[0] <= numTracks)
				{
					final List<TrackSec> thisUnit = new ArrayList<TrackSec>(allocUnitSize);
					while(thisUnit.size() < allocUnitSize)
					{
						final TrackSec newTS = new TrackSec(ts[0], ts[1]);
						if(!skipThese.contains(newTS))
							thisUnit.add(newTS);
						ts[1] = (short)((ts[1] + (short)interleave) % totalSecs);
						if(ts[1] == 0)
						{
							ts[0] = (short)(ts[0] + 1);
							if(ts[0] >= numTracks)
								break;
							totalSecs = type.sectors(ts[0], cpmOs);
						}
					}
					if(thisUnit.size() == allocUnitSize)
						alloBuilder.add(thisUnit.toArray(new TrackSec[allocUnitSize]));
				}
				this.cpmAllocUnits = alloBuilder.toArray(new TrackSec[alloBuilder.size()][]);
			}
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
		if((type == ImageType.T64)
		||(type == ImageType.LBR)
		||(type == ImageType.ARC)
		||(type == ImageType.SDA)
		||(type == ImageType.LNX))
			return bytes[0][0];
		final ByteArrayOutputStream bout=new ByteArrayOutputStream();
		for(int t=1;t<bytes.length;t++)
		{
			if(bytes[t] == null)
				continue;
			for(int s=0;s<bytes[t].length;s++)
			{
				if(bytes[t][s] == null)
					continue;
				try
				{
					bout.write(bytes[t][s]);
				}
				catch (final IOException e)
				{
				}
			}
		}
		return bout.toByteArray();
	}

	public static class BAMInfo
	{
		short	track		= 0;
		short	sector		= 0;
		short	firstByte	= 0;
		short	lastByte	= 0;
		short	freeOffset	= 0;
		short	trackOffset	= 0;
		BAMInfo next		= null;
		public BAMInfo(final int track, final int sector, final int firstByte,
					   final int lastByte, final int freeOffset, final int trackOffset,
					   final BAMInfo next)
		{
			this.track = (short)track;
			this.sector = (short)sector;
			this.firstByte = (short)firstByte;
			this.lastByte = (short)lastByte;
			this.freeOffset = (short)freeOffset;
			this.trackOffset = (short)trackOffset;
			this.next = next;
		}
	}

	// todo: add file masks to options
	public enum ImageType
	{
		D64(".D64", new BAMInfo(18,0,4,143,0,0,null),new TrackSecs(18,0,null),19,35,10,
					new int[] {18,21,25,19,31,18,256,17}),
		D71(".D71", new BAMInfo(18,0,4,143,0,0, new BAMInfo(53,0,0,104,-1,3, null)),
					new TrackSecs(18,0,new TrackSecs(53,0,null)),19,70,5,
					new int[] {18,21,25,19,31,18,36,17,53,21,60,19,66,18,256,17}),
		D81(".D81", new BAMInfo(40,1,16,255,0,0, new BAMInfo(40,2,16,255,0,0, null)),
					new TrackSecs(40,0,null),80,80,1,new int[] {256,40}),
		D80(".D80", new BAMInfo(38,0,6,255,0,0, new BAMInfo(38,3,6,140,0,5, null)),
					new TrackSecs(39,0,null),29,77,10,
					new int[] {40,29,54,27,65,25,78,23,117,29,131,27,142,25,155,23,256,23}),
		D82(".D82", new BAMInfo(38,0,6,255,0,0,new BAMInfo(38,3,6,255,0,5,
					new BAMInfo(38,6,6,255,0,5,new BAMInfo(38,9,6,255,0,5,null)))),
					new TrackSecs(39,0,null),29,154,10,
					new int[] {40,29,54,27,65,25,78,23,117,29,131,27,142,25,155,23,256,23}),
		DNP(".DNP", new BAMInfo(1,2,31,255,0,32,null),new TrackSecs(1,0,null),35,-1,1,
					new int[] {256,256}),
		T64(".T64", null, null, -1, -1, -1, new int[] {256, -1}),
		LNX(".LNX", null, null, -1, -1, -1, new int[] {256, -1}),
		LBR(".LBR", null, null, -1, -1, -1, new int[] {256, -1}),
		ARC(".ARC", null, null, -1, -1, -1, new int[] {256, -1}),
		SDA(".SDA", null, null, -1, -1, -1, new int[] {256, -1})
		;

		public final BAMInfo	bamHead;
		public final String		ext;
		public final TrackSecs	dirHead;
		public final short		dirSecs;
		private final int		numTracks;
		private final short		interleave;
		private final short[]	secMap;

		private ImageType(final String ext, final BAMInfo bam, final TrackSecs head, final int dirSecs,
						  final int numTracks, final int interleave, final int[] secMap)
		{
			this.ext=ext;
			this.bamHead = bam;
			this.dirHead = head;
			this.numTracks = numTracks;
			this.interleave = (short)interleave;
			this.dirSecs = (short)dirSecs;
			if(ext.equals(".DNP"))
			{
				BAMInfo curr = bamHead;
				for(int i=2;i<33;i++)
				{
					curr.next = new BAMInfo(1,i+1,0,255,0,32,null);
					curr = curr.next;
				}
			}
			this.secMap = new short[256];
			int start=0;
			for(int i=0;i<secMap.length;i+=2)
			{
				for(int x=start;x<secMap[i];x++)
					this.secMap[x]=(short)secMap[i+1];
				start=secMap[i];
			}
		}

		private short sectors(final int track, final CPMType cpm)
		{
			if((track<0)||(track>255))
				return 0;
			switch(cpm)
			{
			case C64:
				return 17;
			case CBM:
				return 23;
			case NORMAL:
			case NOT:
			default:
				return secMap[track];
			}
		}

		private int numTracks(final long fileSize)
		{
			if(this.numTracks < 0)
				return (int)(fileSize / 256 / 256);
			if((this.numTracks == 35) && (fileSize >= 349696))
				return 70;
			return this.numTracks;
		}

		private short interleave(final CPMType cpm)
		{
			switch(cpm)
			{
			case C64:
			case CBM:
				return (short)1;
			case NORMAL:
				if(this != ImageType.D81)
					return (short)5;
			//$FALL-THROUGH$
			case NOT:
				return this.interleave;
			}
			return this.interleave;
		}

		public String toString() {
			return ext;
		};
	};

	public enum CPMType
	{
		NOT,
		NORMAL,
		C64,
		CBM
	}

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

		@Override
		public int hashCode()
		{
			return (track << 16) + sector;
		}

	}

	public static class TrackSecs extends TrackSec
	{
		final TrackSecs next;
		private TrackSecs(final int t, final int s, final TrackSecs next)
		{
			super((short)t,(short)s);
			this.next = next;
		}
	}

	public static class FileInfo
	{
		public FileInfo			parentF			= null;
		public String			fileName		= "";
		public byte[]			rawFileName		= new byte[0];
		public String			filePath		= "";
		public FileType			fileType		= null;
		public int				feblocks		= 0;
		public int				size			= 0;
		public byte[]			data			= null;
		public Set<Long>		rollingHashes	= null;
		public Set<Long>		fixedHashes		= null;
		public byte[]			header			= null;
		public short[]			dirLoc			= new short[3];
		public List<TrackSec>	tracksNSecs		= new ArrayList<TrackSec>();
		private long			hash			= -1;

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

	private int getImageTotalBytes(final int fileSize)
	{
		if((type == ImageType.T64)
		||(type == ImageType.LBR)
		||(type == ImageType.ARC)
		||(type == ImageType.SDA)
		||(type == ImageType.LNX))
			return fileSize;
		final int ts=type.numTracks(fileSize);
		int total=0;
		for(int t=1;t<=ts;t++)
			total+=(256*type.sectors(t, cpmOs));
		return total;
	}

	private byte[][][] parseImageDiskMap(final byte[] buf, final int fileLength)
	{
		final int numTS=type.numTracks(fileLength);
		final byte[][][] tsmap=new byte[numTS+1][][];
		int index=0;
		for(int t=1;t<=numTS;t++)
		{
			final int secs=type.sectors(t, cpmOs);
			tsmap[t] = new byte[secs][256];
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
			return new byte[type.numTracks((int)F.length())][255][256];
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
				return new byte[type.numTracks(len)][255][256];
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
			||(type == ImageType.LBR)
			||(type == ImageType.ARC)
			||(type == ImageType.SDA)
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
			return new byte[type.numTracks(len)][255][256];
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
		while((t!=0)&&(t<tsmap.length)
		&&(s<tsmap[t].length)
		&&(!doneBefore.contains(tsmap[t][s]))
		&&(t<=maxT))
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
					final int size=(254*(lb+(256*hb)));
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
							if(readInside && fileT != 0)
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
							if(readInside && fileT != 0)
							{
								fileData =getFileContent(f.fileName,fileT,maxT,fileS,f.tracksNSecs);
								if((fileData != null)&&(fileData.length>0))
									f.size=fileData.length;
							}
							f.fileType = FileType.SEQ;
							break;
						case (byte) 2:
							if(readInside && fileT != 0)
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
							if(readInside && fileT != 0)
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
							if(readInside && fileT != 0)
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
					final BAMInfo curBAM, final short bamByteOffset,
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
			&&((possDTrack!=1)||(type==ImageType.DNP)) // exception for special d71 creations
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

	public interface BAMBack
	{
		public boolean call(short t, short s, boolean set, BAMInfo curBAM, short bamByteOffset, short sumBamByteOffset, short bamMask);
	}

	public void bamPeruse(final BAMBack call) throws IOException
	{
		BAMInfo currBAM = type.bamHead;
		if((currBAM == null) || (cpmOs != CPMType.NOT))
			throw new IOException("Illegal image type.");
		if(call == null)
			return;
		final byte[][][] bytes=getDiskBytes();
		final long imageSize=getLength();
		byte[] bam=bytes[currBAM.track][currBAM.sector];
		short bamOffset = currBAM.firstByte;
		final int numTracks = type.numTracks(imageSize);
		for(int t=1;t<=numTracks;t++)
		{
			if(bamOffset > currBAM.lastByte)
			{
				currBAM = currBAM.next;
				if(currBAM==null)
					throw new IOException("BAM failure");
				bam=bytes[currBAM.track][currBAM.sector];
				bamOffset = currBAM.firstByte;
			}
			final int secsPerTrack = type.sectors(t, cpmOs);
			final int skipByte = currBAM.freeOffset;
			final short skipOffset = (short)((skipByte < 0)?0:1);
			for(int s=0;s<secsPerTrack;s++)
			{
				final short sumBamByteOffset = (short)((skipByte < 0) ? -1 : (bamOffset + skipByte));
				final short bamByteOffset = (short)(bamOffset + skipOffset + (int)Math.round(Math.floor(s/8.0)));
				final short bamByte = (short)(bam[bamByteOffset] & 0xff);
				short mask;
				if(type==ImageType.DNP)
					mask = (short)Math.round(Math.pow(2.0,7-(s%8)));
				else
					mask = (short)Math.round(Math.pow(2.0,s%8));
				//System.out.println(t+","+s+"=>"+currBAM.track+","+currBAM.sector+":"+bamByteOffset+"&"+mask);
				final boolean set = (bamByte & mask) == mask;
				if(call.call((short)t, (short)s, set, currBAM, bamByteOffset, sumBamByteOffset, mask))
					return;
			}
			if(currBAM.trackOffset != 0)
				bamOffset += currBAM.trackOffset;
			else
				bamOffset+=(skipOffset + (int)Math.round(Math.ceil(secsPerTrack/8.0)));
		}
	}

	private TrackSec[] getCPMBlock(final int blockNumber)
	{
		if(blockNumber >= this.cpmAllocUnits.length)
			return null;
		return this.cpmAllocUnits[blockNumber];
	}

	private List<short[]> getFirstCPMFreeDirEntries()
	{
		final byte[][][] diskBytes = this.getDiskBytes();
		final List<TrackSec> allDirSecs = new ArrayList<TrackSec>();
		for(int bn = 0; bn < 1; bn++)
		{
			for(final TrackSec ts : getCPMBlock(bn))
				allDirSecs.add(ts);
		}
		final List<short[]> allEntries = new ArrayList<short[]>();
		for(final TrackSec dts : allDirSecs)
		{
			final byte[] blk = diskBytes[dts.track][dts.sector];
			for(int e=0;e<8;e++)
			{
				final int offset = e * 32;
				final int userId = (blk[offset] & 0xff);
				if(userId == 0xe5)
					allEntries.add(new short[] { dts.track, dts.sector, (short)offset });
			}
		}
		return allEntries;
	}

	private List<FileInfo> getCPMFiles(final BitSet parseFlags)
	{
		final byte[][][] diskBytes = this.getDiskBytes();
		final List<FileInfo> finalData=new Vector<FileInfo>();
		final Map<String, FileInfo> prevEntries = new HashMap<String, FileInfo>();
		final boolean readInside = parseFlags.get(PF_READINSIDE);
		final List<TrackSec> allDirSecs = new ArrayList<TrackSec>();
		for(int bn = 0; bn < 1; bn++)
			for(final TrackSec ts : getCPMBlock(bn))
				allDirSecs.add(ts);
		for(final TrackSec dts : allDirSecs)
		{
			final byte[] blk = diskBytes[dts.track][dts.sector];
			for(int e=0;e<8;e++)
			{
				final int offset = e * 32;
				final int userId = (blk[offset] & 0xff);
				if(userId >= 32) // $32 = directory label, $33 date/time, >= $e5 empty
					continue;
				// fix extension flags
				final byte[] fnblock = Arrays.copyOfRange(blk, offset+1, offset+12);
				final int exNumber = (blk[offset+12] & 0xff); // extend number lower byte (0-31?!)
				final int s1Number = (blk[offset+13] & 0xff); // extend number upper byte
				//final int s2Number = (blk[offset+14] & 0xff); // last record byte count (rare)
				final int rcNumber = (blk[offset+15] & 0xff); // record count, this extend
				//final boolean readonly = (fnblock[8] & 0x80) == 0x80;
				//final boolean hidden = (fnblock[9]  & 0x80) == 0x80;
				//final boolean archive = (fnblock[10]  & 0x80) == 0x80;
				fnblock[8] = (byte)(fnblock[8] & 0x7f);
				fnblock[9] = (byte)(fnblock[9] & 0x7f);
				fnblock[10] = (byte)(fnblock[10] & 0x7f);
				String rawName = new String(fnblock);
				rawName = rawName.substring(0,8).trim() + "." + rawName.substring(8).trim();
				final FileInfo file;
				if(prevEntries.containsKey(rawName))
					file = prevEntries.get(rawName);
				else
				{
					file = new FileInfo();
					file.fileName = rawName;
					file.rawFileName = fnblock;//Arrays.copyOfRange(blk, offset+1, offset+12);
					if(rawName.endsWith(".COM"))
						file.fileType = FileType.PRG;
					else
						file.fileType = FileType.CBM;
					file.size = 0;
					finalData.add(file);
					prevEntries.put(rawName, file);
					file.dirLoc = new short[0];
					file.data = new byte[0];
					file.feblocks = 0;
					file.filePath=rawName;
					file.header=null; // no geos files in cp/m
				}
				final short[] dirLocs = file.dirLoc;
				file.dirLoc = Arrays.copyOf(dirLocs, dirLocs.length+3);
				file.dirLoc[file.dirLoc.length-3] = dts.track;
				file.dirLoc[file.dirLoc.length-2] = dts.sector;
				file.dirLoc[file.dirLoc.length-1] = (short)offset;
				final int fullExNumber = exNumber + (256 * s1Number);
				int recordsRemaining = ((fullExNumber * 128)-(file.size/128)) + rcNumber;
				file.size += (recordsRemaining * 128);
				file.feblocks = (int)Math.round(Math.ceil(file.size / 254.0));
				if(readInside)
				{
					final boolean bit16 = getType() == ImageType.D81;
					for(int i=16;(i<32) && (recordsRemaining >0);i++)
					{
						int blockNum;
						blockNum = blk[offset + i] & 0xff;
						if(bit16)
						{
							i++;
							blockNum += (blk[offset + i] & 0xff) * 256;
						}
						if(blockNum == 0)
							continue;
						final TrackSec[] secs = this.getCPMBlock(blockNum);
						if(secs == null)
						{
							if(!parseFlags.get(D64Base.PF_NOERRORS))
								errMsg("Illegal block num in file "+file.fileName+": "+blockNum);
							continue;
						}
						for(final TrackSec ts : secs)
						{
							if(recordsRemaining == 1)
							{
								file.tracksNSecs.add(ts);
								file.data = Arrays.copyOf(file.data, file.data.length + 128);
								final byte[] dblk = diskBytes[ts.track][ts.sector];
								for(int di=0;di<128;di++)
									file.data[file.data.length-128+di] = dblk[di];
								recordsRemaining--;
							}
							else
							if(recordsRemaining > 1)
							{
								file.tracksNSecs.add(ts);
								file.data = Arrays.copyOf(file.data, file.data.length + 256);
								final byte[] dblk = diskBytes[ts.track][ts.sector];
								for(int di=0;di<256;di++)
									file.data[file.data.length-256+di] = dblk[di];
								recordsRemaining-=2;
							}
						}
					}
				}
			}
		}
		return finalData;

	}

	public List<FileInfo> getFiles(final BitSet parseFlags)
	{
		final String imgName = F.getName();
		if(type == ImageType.T64)
		{
			final byte[][][] tsmap = this.getDiskBytes();
			final int fileSize = getLength();
			return T64.getTapeFiles(tsmap,fileSize,imgName,parseFlags);
		}
		else
		if(type == ImageType.LNX)
		{
			try
			{
				return Lynx.getLNXDeepContents(this.getFlatBytes());
			}
			catch(final IOException e)
			{
				errMsg(e.getMessage());
				return new ArrayList<FileInfo>();
			}
		}
		else
		if(type == ImageType.LBR)
		{
			try
			{
				return Library.getLNXDeepContents(getFlatBytes());
			}
			catch(final IOException e)
			{
				errMsg(e.getMessage());
				return new ArrayList<FileInfo>();
			}
		}
		else
		if((type == ImageType.ARC)||(type == ImageType.SDA))
		{
			try
			{
				return Arc.getARCDeepContents(this.getFlatBytes());
			}
			catch(final IOException e)
			{
				errMsg(e.getMessage());
				return new ArrayList<FileInfo>();
			}
		}
		else
		{
			if(cpmOs != CPMType.NOT)
				return getCPMFiles(parseFlags);
		}
		final byte[][][] tsmap = this.getDiskBytes();
		final int fileSize = getLength();
		int t=type.dirHead.track;
		final int maxT=type.numTracks( fileSize);
		int s=type.dirHead.sector;
		final List<FileInfo> finalData=new Vector<FileInfo>();
		BAMInfo currBAM = type.bamHead;
		FileInfo f=new FileInfo();
		f.dirLoc=new short[]{currBAM.track,currBAM.sector,currBAM.firstByte};
		f.fileName="*BAM*";
		f.filePath="*BAM*";
		f.fileType=FileType.DIR;
		f.size=0;
		f.feblocks=0;
		f.tracksNSecs.add(TrackSec.valueOf((short)t,(short)s));
		finalData.add(f);
		if(currBAM.sector!=0)
			f.tracksNSecs.add(TrackSec.valueOf(currBAM.track,(short)0));
		while(currBAM != null)
		{
			f.tracksNSecs.add(TrackSec.valueOf(currBAM.track,currBAM.sector));
			currBAM = currBAM.next;
		}
		switch(type)
		{
		case D80:
			currBAM = type.bamHead;
			currBAM = currBAM.next;
			// sometimes a d80 is formatted like an 8250 if user fail.
			t=type.dirHead.track;
			s=1;
			break;
		case D82:
			currBAM = type.bamHead;
			currBAM = currBAM.next;
			currBAM = currBAM.next;
			currBAM = currBAM.next;
			t=unsigned(tsmap[currBAM.track][currBAM.sector][0]);
			s=unsigned(tsmap[currBAM.track][currBAM.sector][1]);
			break;
		case DNP:
			t=type.dirHead.track;
			s=1; // first dir block
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
		{
			final int numSectors = type.sectors(53, cpmOs);
			for(int sec=1;sec<numSectors;sec++)
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
		}
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

	private short[] findFreeSector(final short startTrack, final short stopTrack, final short dir, final Set<Integer> skip) throws IOException
	{
		short track=startTrack;
		short numFree=0;
		final short interleave=type.interleave(cpmOs);
		while(track!=stopTrack)
		{
			numFree=sectorsFreeOnTrack( track);
			if(numFree > 0)
			{
				final int secsOnTrack=type.sectors(track, cpmOs);
				final Set<Integer> doneThisTrack = new TreeSet<Integer>();
				short s = 0;
				while(doneThisTrack.size()<secsOnTrack)
				{
					Integer sint = Integer.valueOf((track<<8)+s);
					while(doneThisTrack.contains(sint))
					{
						s = (short)((s + 1) % secsOnTrack);
						sint = Integer.valueOf((track<<8)+s);
					}
					if((skip==null)||(!skip.contains(sint)))
					{
						if(!isSectorAllocated(track,s))
							return new short[]{track,s};
					}
					doneThisTrack.add(sint);
					s = (short)((s + interleave) % secsOnTrack);
				}
			}
			track+=dir;
		}
		return null;
	}

	private short[] nextFreeSectorInDirection(final short dirTrack, final short startTrack,
											  final short firstTrack, final short lastTrack,
											  final Set<Integer> skip) throws IOException
	{
		if(startTrack == dirTrack)
		{
			final short[] tryBelow = nextFreeSectorInDirection(dirTrack,(short)(dirTrack-1),firstTrack,lastTrack,skip);
			final short[] tryAbove = nextFreeSectorInDirection(dirTrack,(short)(dirTrack+1),firstTrack,lastTrack,skip);
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
			short searchTrack = dirTrack;
			if(dirTrack < 0)
				searchTrack = 0;
			else
			if(startTrack<dirTrack)
			{
				dir=-1;
				stopTrack=firstTrack;
			}
			return findFreeSector((short)(searchTrack+dir),stopTrack,dir,skip);
		}
	}

	private short[] firstFreeSector(final Set<Integer> skip) throws IOException
	{
		final int imageFLen = getLength();
		final short dirTrack=type.dirHead.track;
		final short lastTrack=(short)type.numTracks(imageFLen);
		if(type == ImageType.D71)
		{
			short[] sector = nextFreeSectorInDirection(dirTrack,dirTrack,(short)0,(short)35,skip);
			if(sector == null)
				sector = nextFreeSectorInDirection((short)53,(short)53,(short)35,lastTrack,skip);
			return sector;
		}
		else
		if(type == ImageType.D82)
		{
			short[] sector = nextFreeSectorInDirection(dirTrack,dirTrack,(short)0,(short)77,skip);
			if(sector == null)
				sector = nextFreeSectorInDirection((short)116,(short)116,(short)77,lastTrack,skip);
			return sector;
		}
		else
		if(type == ImageType.DNP)
			return nextFreeSectorInDirection((short)-1,dirTrack,(short)0,lastTrack,skip);
		else
		{
			return nextFreeSectorInDirection(dirTrack,dirTrack,(short)0,lastTrack,skip);
		}
	}

	private List<short[]> getFreeSectors(final int numSectors, final short[] skipDirSec) throws IOException
	{
		if((type == ImageType.LNX)
		||(type == ImageType.LBR)
		||(type == ImageType.ARC)
		||(type == ImageType.SDA)
		||(type == ImageType.T64)
		||(cpmOs != CPMType.NOT))
			throw new IOException("Illegal image type.");
		final int imageFLen = getLength();
		final List<short[]> list=new ArrayList<short[]>();
		if(totalSectorsFree()<numSectors)
			throw new IOException("Not enough free space.");
		short[] ts=null;
		final short lastTrack=(short)type.numTracks(imageFLen);
		final short dirTrack=type.dirHead.track;
		final Set<Integer> skip=new HashSet<Integer>();
		if(skipDirSec != null)
			skip.add(Integer.valueOf((skipDirSec[0]<<8)+skipDirSec[1]));
		TrackSecs ks = type.dirHead;
		while(ks != null)
		{
			for(int i=0;i<type.dirSecs;i++)
				skip.add(Integer.valueOf((ks.track<<8)+i));
			ks=ks.next;
		}
		BAMInfo bs = type.bamHead;
		while(bs != null)
		{
			skip.add(Integer.valueOf((bs.track<<8)+bs.sector));
			bs=bs.next;
		}
		int tries = numSectors*10;
		while((list.size()<numSectors)&&(--tries>0))
		{
			if(ts != null)
			{
				final short prevTrack=ts[0];
				if(type == ImageType.D71)
				{
					if(prevTrack<=35)
					{
						ts = nextFreeSectorInDirection(dirTrack,prevTrack,(short)0,(short)35,skip);
						if(ts == null)
							ts = nextFreeSectorInDirection((short)53,(short)53,(short)35,lastTrack,skip);
					}
					else
						ts = nextFreeSectorInDirection((short)53,prevTrack,(short)0,lastTrack,skip);
				}
				else
				if(type == ImageType.D82)
				{
					if(prevTrack<=77) // 35 can't be right, can it?  thats a 4040 disk!
					{
						ts = nextFreeSectorInDirection(dirTrack,prevTrack,(short)0,(short)77,skip);
						if(ts == null)
							ts = nextFreeSectorInDirection((short)116,(short)116,(short)77,lastTrack,skip);
					}
					else
						ts = nextFreeSectorInDirection((short)116,prevTrack,(short)0,lastTrack,skip);
				}
				else
				if(type == ImageType.DNP)
					ts = nextFreeSectorInDirection((short)-1,dirTrack,(short)0,lastTrack,skip);
				else
					ts = nextFreeSectorInDirection(dirTrack,prevTrack,(short)0,lastTrack,skip);
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
					final BAMInfo curBAM, final short bamByteOffset,
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
		return (short)(type.sectors(track, cpmOs) - numAllocated[0]);
	}

	private int totalSectorsFree() throws IOException
	{
		final int[] numAllocated = new int[]{0};
		final int[] numFree = new int[]{0};
		bamPeruse(new BAMBack()
		{
			@Override
			public boolean call(final short t, final short s, final boolean set,
					final BAMInfo curBAM, final short bamByteOffset,
					final short sumBamByteOffset, final short bamMask)
			{
				if(!set)
					numAllocated[0]++;
				else
				{
					TrackSecs head = type.dirHead;
					while(head != null)
					{
						if((t == head.track)
						&&(s<type.dirSecs))
							break;
						head = head.next;
					}
					if(head == null)
						numFree[0]++;
				}
				return false;
			}
		});
		return numFree[0];
	}

	private Integer[] getFreeCPMBlocks()
	{
		final byte[][][] diskBytes = getDiskBytes();
		final BitSet flags = new BitSet(D64Mod.PF_NOERRORS);
		final List<FileInfo> files = this.getCPMFiles(flags);
		if(files == null)
			return null;
		final Set<Integer> freeBlocks = new TreeSet<Integer>();
		for(int i=2;i<cpmAllocUnits.length;i++) // always start at 2
			freeBlocks.add(Integer.valueOf(i));
		final boolean bit16 = getType() == ImageType.D81;
		for(final FileInfo file : files)
		{
			for(int i = 0;i<file.dirLoc.length;i+=3)
			{
				final short track =file.dirLoc[i+0];
				final short sector =file.dirLoc[i+1];
				final short offset =file.dirLoc[i+2];
				final byte[] dirBlk =  diskBytes[track][sector];
				for(int b=16;b<32;b++)
				{
					int blockNum = (dirBlk[offset+b] & 0xff);
					if(bit16)
					{
						b++;
						blockNum += ((dirBlk[offset+b] & 0xff) * 256);
					}
					if(blockNum != 0)
						freeBlocks.remove(Integer.valueOf(blockNum));
				}
			}
		}
		return freeBlocks.toArray(new Integer[freeBlocks.size()]);
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

	public boolean scratchCPMFile(final FileInfo file) throws IOException
	{
		if((file.dirLoc == null)||(file.dirLoc.length<3))
			return false;
		final byte[][][] diskBytes = this.getDiskBytes();
		boolean didOne=false;
		for(int i = 0;i<file.dirLoc.length;i+=3)
		{
			final short track =file.dirLoc[i+0];
			final short sector =file.dirLoc[i+1];
			final short offset =file.dirLoc[i+2];
			diskBytes[track][sector][offset] = (byte)0xe5;
			didOne=true;
		}
		return didOne;
	}

	public boolean scratchFile(final FileInfo file) throws IOException
	{
		final byte[][][] diskBytes = this.getDiskBytes(); // force load for cpm
		if(type == ImageType.T64)
			return scratchTapeFile(file);
		if(type == ImageType.LNX)
			return false; //TODO:
		if(type == ImageType.LBR)
			return false; //TODO:
		if(type == ImageType.ARC)
			return false; //TODO:
		if(type == ImageType.SDA)
			return false; //TODO:
		if(cpmOs != CPMType.NOT)
			return scratchCPMFile(file);

		final byte[] dirSector = diskBytes[file.dirLoc[0]][file.dirLoc[1]];
		dirSector[file.dirLoc[2]]=(byte)(0);
		final Set<Integer> tsSet=new HashSet<Integer>();
		for(final TrackSec ts : file.tracksNSecs)
			tsSet.add(Integer.valueOf((ts.track << 8) + ts.sector));

		bamPeruse(new BAMBack()
		{
			@Override
			public boolean call(final short t, final short s, final boolean set,
					final BAMInfo curBAM, final short bamByteOffset,
					final short sumBamByteOffset, final short bamMask)
			{

				final Integer tsInt=Integer.valueOf((t << 8) + s);
				if(tsSet.contains(tsInt)&&(!set))
				{
					final byte[] bamSector = diskBytes[curBAM.track][curBAM.sector];
					if(sumBamByteOffset>=0)
					{
						try
						{
							final int totalSectors=type.sectors(t, cpmOs);
							final int sectorsFree=sectorsFreeOnTrack(t);
							if((sectorsFree<=totalSectors)
							&&(((bamSector[sumBamByteOffset]&0xff)==sectorsFree)))
								bamSector[sumBamByteOffset] = (byte)((bamSector[sumBamByteOffset]&0xff)-1);
						}
						catch(final Exception e)
						{
						}
					}
					else
					if(type == ImageType.D71) // the very special very messed up exception
					{
						try
						{
							final byte[] numFreeBytes = diskBytes[type.bamHead.track][type.bamHead.sector];
							final int totalSectors=type.sectors(t, cpmOs);
							final int sectorsFree=sectorsFreeOnTrack(t);
							final int specialOffset = 185+t;
							if((sectorsFree<=totalSectors)
							&&(((numFreeBytes[specialOffset]&0xff)==sectorsFree)))
								numFreeBytes[specialOffset] = (byte)((numFreeBytes[specialOffset]&0xff)+1);
						}
						catch(final Exception e)
						{
						}
					}
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
			if((sector[i]==(byte)128)
			||(sector[i]&(byte)128)==0)
				return new short[]{dirts.track,dirts.sector,(short)i};
		}
		return null;
	}

	private short[] findDirectorySlot(final FileInfo parentDir) throws IOException
	{
		if((type == ImageType.LNX)
		||(type == ImageType.LBR)
		||(type == ImageType.ARC)
		||(type == ImageType.SDA)
		||(type == ImageType.T64)
		||(cpmOs != CPMType.NOT))
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
			final int secsOnTrack=type.sectors(track, cpmOs);
			for(short s1=0;s1<interleave;s1++)
			{
				for(short s=s1;s<secsOnTrack;s+=interleave)
				{
					if(!isSectorAllocated(track,s))
					{
						sec[0]=(byte)(track & 0xff);
						sec[1]=(byte)(s & 0xff);
						final byte[] newSec = diskBytes[track][s];
						for(int i=0;i<256;i++)
							newSec[i]=0;
						newSec[1]=(byte)0xff;
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
			short[] ts= findFreeSector(track,(short)(type.numTracks(imageFLen)+1),(short)1,null);
			if(ts == null)
				ts= findFreeSector((short)1,(short)(type.numTracks(imageFLen)+1),(short)1,null);
			if(ts == null)
				throw new IOException("No free sectors found for dir entry.");
			sec[0]=(byte)(ts[0] & 0xff);
			sec[1]=(byte)(ts[1] & 0xff);
			final byte[] newSec = diskBytes[ts[0]][ts[1]];
			for(int i=0;i<256;i++)
				newSec[i]=0;
			newSec[1] = (byte)0xff;
			final List<short[]> allocs=new ArrayList<short[]>();
			allocs.add(new short[]{ts[0],ts[1]});
			allocateSectors(allocs);
			return new short[]{ts[0],ts[1],2};
		}
	}

	private boolean allocateSectors(final List<short[]> sectors) throws IOException
	{
		if((type == ImageType.LNX)
		||(type == ImageType.LBR)
		||(type == ImageType.ARC)
		||(type == ImageType.SDA)
		||(type == ImageType.T64)
		||(cpmOs != CPMType.NOT))
			return false;
		final Set<Integer> secsToDo=new HashSet<Integer>();
		for(final short[] sec : sectors)
			secsToDo.add(Integer.valueOf((sec[0] << 8) + sec[1]));
		bamPeruse(new BAMBack()
		{
			@Override
			public boolean call(final short t, final short s, final boolean set,
					final BAMInfo curBAM, final short bamByteOffset,
					final short sumBamByteOffset, final short bamMask)
			{

				final Integer tsInt=Integer.valueOf((t << 8) + s);
				if(secsToDo.contains(tsInt)&&(set))
				{
					final byte[] bamSector = diskBytes[curBAM.track][curBAM.sector];
					//System.out.println(t+","+s+" => "+curBAM.track+","+curBAM.sector+","+bamByteOffset+","+bamMask);
					if(sumBamByteOffset>=0)
					{
						try
						{
							final int totalSectors=type.sectors(t, cpmOs);
							final int sectorsFree=sectorsFreeOnTrack(t);
							if((sectorsFree<=totalSectors)
							&&(((bamSector[sumBamByteOffset]&0xff)==sectorsFree)))
								bamSector[sumBamByteOffset] = (byte)((bamSector[sumBamByteOffset]&0xff)-1);
						}
						catch(final Exception e)
						{
						}
					}
					else
					if(type == ImageType.D71) // the very special very messed up exception
					{
						try
						{
							final byte[] numFreeBytes = diskBytes[type.bamHead.track][type.bamHead.sector];
							final int totalSectors=type.sectors(t, cpmOs);
							final int sectorsFree=sectorsFreeOnTrack(t);
							final int specialOffset = 185+t;
							//System.out.println(specialOffset+", "+t+":"+(numFreeBytes[specialOffset]&0xff)+"/"+sectorsFree+"/"+totalSectors);
							if((sectorsFree<=totalSectors)
							&&(((numFreeBytes[specialOffset]&0xff)==sectorsFree)))
								numFreeBytes[specialOffset] = (byte)((numFreeBytes[specialOffset]&0xff)-1);
						}
						catch(final Exception e)
						{
						}
					}
					bamSector[bamByteOffset] = (byte)(bamSector[bamByteOffset] & (255-bamMask));
				}
				return false;
			}
		});
		return true;
	}

	private boolean insertT64File(final String targetFileName, final byte[] fileData, final FileType cbmtype)
	{
		byte[] data = diskBytes[0][0];
		final int numEntries = (data[34] & 0xff) + (256 * (data[35] & 0xff));
		final int usedEntries = (data[36] & 0xff)+ (256 * (data[37] & 0xff));
		int dirStart=-1;
		if(numEntries > usedEntries)
		{
			for(int i = 64; i<64+(32*numEntries); i+=32)
			{
				if(data[i]==0)
				{
					dirStart=i;
					break;
				}
			}
		}
		if(dirStart<0)
		{
			// make new dir entry:
			final byte[] start = Arrays.copyOfRange(data, 0, 64 + (32 * numEntries));
			final byte[] rest = Arrays.copyOfRange(data, 64 + (32 * numEntries), data.length);
			data = Arrays.copyOf(start, start.length+rest.length+32);
			for(int i=0;i<rest.length;i++)
				data[start.length+i+32] = rest[i];
			final byte[] numEntriesB = D64Base.numToBytes(numEntries+1);
			data[34] = numEntriesB[0];
			data[35] = numEntriesB[1];
			dirStart = 64 + (32 * numEntries);
			//now fix ALL the file start offsets, as they are **ALL WRONG**
			for(int sx = 64; sx<64+(32*numEntries); sx+=32)
			{
				if(data[sx]!=1)
					continue;
				final int dataOffset =  (data[sx+8] & 0xff) // since we are adding a dir entry, the offset moves up
						+ (256*(data[sx+9] & 0xff))
						+ (65536 * (data[sx+10] & 0xff)); // nothing higher is Good.
				final byte[] dataOffsetB = D64Base.numToBytes(dataOffset+32);
				data[sx+8] = dataOffsetB[0];
				data[sx+9] = dataOffsetB[1];
				data[sx+10] = dataOffsetB[3];
				data[sx+11] = 0;
			}
		}
		final int endOfCurrData = data.length;
		final byte[] usedEntriesB = D64Base.numToBytes(usedEntries+1);
		data[36] = usedEntriesB[0];
		data[37] = usedEntriesB[1];
		// Now actually compose the new dir entry at dirStart
		data[dirStart + 0] = 1;
		data[dirStart + 1] = (byte)0x82;
		if((fileData.length > 1)&&(cbmtype == FileType.PRG))
		{
			data[dirStart+2] = fileData[0];
			data[dirStart+3] = fileData[1];
			final int startAddr = (fileData[0]&0xff) + (256 * (fileData[1]&0xff));
			final byte[] endAddrB = D64Base.numToBytes(fileData.length + startAddr - 1);
			data[dirStart+4] = endAddrB[0];
			data[dirStart+5] = endAddrB[1];
		}
		else
		{
			data[dirStart+2] = 1;
			data[dirStart+3] = 8;
			final byte[] endAddrB = D64Base.numToBytes(fileData.length + 2049 - 1);
			data[dirStart+4] = endAddrB[0];
			data[dirStart+5] = endAddrB[1];
		}
		final byte[] newFileOffset = D64Base.numToBytes(data.length);
		data[dirStart+8] = newFileOffset[0];
		data[dirStart+9] = newFileOffset[1];
		data[dirStart+10] = newFileOffset[2];
		data[dirStart+11] = newFileOffset[3];
		for(int i=0;i<16;i++)
		{
			if(i<targetFileName.length())
				data[dirStart+16+i]=tobyte(D64Base.convertToPetscii(tobyte(targetFileName.charAt(i))));
			else
				data[dirStart+16+i]=tobyte(32);
		}
		// and lastly, append the file data
		data = Arrays.copyOf(data, endOfCurrData + fileData.length);
		for(int i=0;i<fileData.length;i++)
			data[endOfCurrData + i] = fileData[i];
		diskBytes[0][0] = data;
		return true;
	}

	private int getCPMAllocUnits(final byte[][][] diskBytes)
	{
		switch(cpmOs)
		{
		case C64:
			return 4;
		case NORMAL:
			if(getType()==ImageType.D64)
			{
				if(diskBytes[1][0][255] != (byte)0xff)
					return 4;
			}
		default:
		case CBM:
		case NOT:
			return 8;
		}
	}

	private boolean insertCPMFile(String targetFileName, final byte[] fileData)
	{
		final byte[][][] diskBytes = getDiskBytes(); // force load for cpm
		final int allocUnitSecSize = getCPMAllocUnits(diskBytes);
		final double allocUnitBytes = allocUnitSecSize * 256.0;
		final int numAllocUnits = (int)Math.round(Math.ceil(fileData.length / allocUnitBytes));
		final List<Integer> freeAllocUnits = new ArrayList<Integer>();
		freeAllocUnits.addAll(Arrays.asList(getFreeCPMBlocks()));
		if(freeAllocUnits.size() < numAllocUnits)
			return false;
		final boolean bit16 = getType() == ImageType.D81;
		final double unitsPerDirEntry = bit16 ? 8 : 16;
		final int numDirEntriesNeeded = (int)Math.round(Math.ceil(numAllocUnits / unitsPerDirEntry));
		final List<short[]> dirEntries = this.getFirstCPMFreeDirEntries();
		if(dirEntries.size() < numDirEntriesNeeded)
			return false;
		String extension3="   ";
		String filename8="        ";
		targetFileName = targetFileName.toUpperCase();
		final int extx = targetFileName.lastIndexOf('.');
		if((extx==0)
		&&(targetFileName.length()>4))
			filename8=(targetFileName+filename8).substring(0,8);
		else
		if(extx<0)
			filename8=(targetFileName+filename8).substring(0,8);
		else
		{
			filename8=(targetFileName.substring(0,extx)+filename8).substring(0,8);
			extension3=(targetFileName.substring(extx+1)+filename8).substring(0,3);
		}
		final String reFileName;
		if(extension3.trim().length()==0)
			reFileName = filename8.trim();
		else
			reFileName = filename8.trim() + "." + extension3.trim();
		if(reFileName.trim().length()==0)
			return false;
		final FileInfo chkF = this.findFile(reFileName, true, new BitSet(0));
		if(chkF != null)
			return false;
		int fullExNumber = 0;
		int dataPos = 0;
		final int maxBytesPerExtend = (int)Math.round(unitsPerDirEntry * allocUnitBytes);
		for(int deDex = 0; deDex < numDirEntriesNeeded; deDex++)
		{
			final short[] deRef = dirEntries.get(deDex);
			final short dt = deRef[0];
			final short ds = deRef[1];
			final byte[] blk = diskBytes[dt][ds];
			final short offset = deRef[2];
			blk[offset] = 0;
			for(int i=0;i<8;i++)
				blk[offset+i+1] = (byte)(filename8.charAt(i) & 0xff);
			for(int i=0;i<3;i++)
				blk[offset+i+9] = (byte)(extension3.charAt(i) & 0xff);
			final int exh = (int)Math.round(Math.floor(fullExNumber/256.0));
			final int exl = fullExNumber - (exh * 256);
			blk[offset+12] = (byte)(exl & 0xff);
			blk[offset+13] = (byte)(exh & 0xff);
			blk[offset+14] = (byte)0;
			int bytesThisExtend = (fileData.length - dataPos);
			if(bytesThisExtend > maxBytesPerExtend)
				bytesThisExtend = maxBytesPerExtend;
			blk[offset+15] = (byte)(Math.round(Math.ceil(bytesThisExtend / 128.0)) & 0xff);
			for(int o=16;o<32;o++)
			{
				if(dataPos < fileData.length)
				{
					final Integer blockNum = freeAllocUnits.remove(0);
					if(bit16)
					{
						final int bnh = (int)Math.round(Math.floor(blockNum.intValue()/256.0));
						final int bnl = blockNum.intValue() - (bnh * 256);
						blk[offset+o] = (byte)bnl;
						o++;
						blk[offset+o] = (byte)bnh;
					}
					else
						blk[offset+o] = (byte)blockNum.intValue();
					final TrackSec[] dsecs = this.getCPMBlock(blockNum.intValue());
					if(dsecs == null)
					{
						errMsg("Unable to allocate "+reFileName);
						return false;
					}
					for(final TrackSec ts : dsecs)
					{
						final byte[] block = diskBytes[ts.track][ts.sector];
						for(int b=dataPos;b<(dataPos + 256) && (b<fileData.length);b++)
							block[b-dataPos] = fileData[b];
						dataPos += 256;
					}
				}
				else
					blk[offset+o] = 0;
			}
			fullExNumber++;
		}
		return true;
	}

	public boolean insertFile(final FileInfo targetDir, final String targetFileName, final byte[] fileData,
			final FileType cbmtype) throws IOException
	{
		final byte[][][] diskBytes = getDiskBytes(); // force load for cpm
		if(cpmOs != CPMType.NOT)
			return insertCPMFile(targetFileName, fileData);
		if(getType() == ImageType.LNX)
			return false;  //TODO:
		if(getType() == ImageType.LBR)
			return false; //TODO:
		if(getType() == ImageType.ARC)
			return false; //TODO:
		if(getType() == ImageType.SDA)
			return false; //TODO:
		if(getType() == ImageType.T64) // tape crap is done above
			return insertT64File(targetFileName, fileData, cbmtype);

		final short[] dirSlot = findDirectorySlot(targetDir);
		if(dirSlot == null)
			throw new IOException("No directory space for "+F.getAbsolutePath());
		//** NOW we can insert into a real Disk Image
		final int sectorsNeeded = (int)Math.round(Math.ceil(fileData.length / 254.0));
		final List<short[]> sectorsToUse = getFreeSectors(sectorsNeeded,dirSlot);
		if((sectorsToUse==null)||(sectorsToUse.size()<sectorsNeeded))
			throw new IOException("Not enough space on disk for "+F.getAbsolutePath());
		int bufDex = 0;
		int secDex = 0;
		while(bufDex < fileData.length)
		{
			if(secDex >= sectorsToUse.size())
				throw new IOException("Not enough sectors found for "+F.getAbsolutePath());
			final short[] sec = sectorsToUse.get(secDex++);
			final byte[] secBlock = diskBytes[sec[0]][sec[1]];
			int bytesToWrite=254;
			if(fileData.length-bufDex<254)
				bytesToWrite=fileData.length-bufDex;
			Arrays.fill(secBlock, (byte)0);
			for(int i=2;i<2+bytesToWrite;i++)
				secBlock[i]=fileData[bufDex+i-2];
			if(secDex < sectorsToUse.size())
			{
				final short[] nextSec = sectorsToUse.get(secDex);
				secBlock[0]=(byte)(nextSec[0] & 0xff);
				secBlock[1]=(byte)(nextSec[1] & 0xff);
			}
			else
			if(fileData.length-bufDex<=254)
			{
				secBlock[0]=0;
				secBlock[1]=(byte)(1+bytesToWrite);
			}
			else
				throw new IOException("Not enough sectors available for "+F.getAbsolutePath());
			bufDex += bytesToWrite;
		}
		if(secDex<sectorsToUse.size())
			throw new IOException("Too many sectors found for "+F.getAbsolutePath());
		final byte[] dirSec=diskBytes[dirSlot[0]][dirSlot[1]];
		final short dirByte=dirSlot[2];
		switch(cbmtype)
		{
		case CBM:
			dirSec[dirByte]=tobyte(5+128);
			break;
		case DEL:
			dirSec[dirByte]=tobyte(0+128);
			break;
		case DIR:
			dirSec[dirByte]=tobyte(6+128);
			break;
		case PRG:
			dirSec[dirByte]=tobyte(2+128);
			break;
		case REL:
			dirSec[dirByte]=tobyte(4+128);
			break;
		case SEQ:
			dirSec[dirByte]=tobyte(1+128);
			break;
		case USR:
			dirSec[dirByte]=tobyte(3+128);
			break;
		}
		dirSec[dirByte+1]=tobyte(sectorsToUse.get(0)[0]);
		dirSec[dirByte+2]=tobyte(sectorsToUse.get(0)[1]);
		for(int i=3;i<=18;i++)
		{
			final int fnoffset=i-3;
			if(fnoffset<targetFileName.length())
				dirSec[dirByte+i]=tobyte(D64Base.convertToPetscii(tobyte(targetFileName.charAt(fnoffset))));
			else
				dirSec[dirByte+i]=tobyte(160);
		}
		for(int i=19;i<=27;i++)
			dirSec[dirByte+i]=0;
		if(getType()==ImageType.DNP)
		{
			final Calendar C=Calendar.getInstance();
			int year=C.get(Calendar.YEAR);
			year-=(int)Math.round(Math.floor(year/100.0))*100;
			dirSec[dirByte+23]=tobyte(year);
			dirSec[dirByte+24]=tobyte(C.get(Calendar.MONTH)+1);
			dirSec[dirByte+25]=tobyte(C.get(Calendar.DAY_OF_MONTH));
			dirSec[dirByte+26]=tobyte(C.get(Calendar.HOUR_OF_DAY));
			dirSec[dirByte+27]=tobyte(C.get(Calendar.MINUTE));
		}
		final int szHB = (int)Math.round(Math.floor(sectorsToUse.size() / 256.0));
		final int szLB = sectorsToUse.size() - (szHB * 256);
		dirSec[dirByte+28]=tobyte(szLB);
		dirSec[dirByte+29]=tobyte(szHB);
		allocateSectors(sectorsToUse);
		return true;
	}

	public FileInfo findFile(final String fileStr, final boolean caseInsensitive)
	{
		final BitSet flags = new BitSet(PF_NOERRORS);
		flags.set(PF_READINSIDE);
		flags.set(PF_RECURSE);
		return findFile(fileStr, caseInsensitive, flags);
	}

	public FileInfo findFile(final String imageFileStr, final boolean caseInsensitive, final BitSet flags)
	{
		final List<FileInfo> files = this.getFiles(flags);
		if(imageFileStr.length()>0)
		{
			if(caseInsensitive)
			{
				for(final FileInfo f : files)
				{
					if(f.filePath.equalsIgnoreCase(imageFileStr))
						return f;
				}
			}
			else
			{
				for(final FileInfo f : files)
				{
					if(f.filePath.equals(imageFileStr))
						return f;
				}
			}
		}
		return null;
	}

}
