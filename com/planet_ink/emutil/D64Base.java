package com.planet_ink.emutil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;
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

	public D64Base() {}
	
	final static String spaces="                                                                                                               ";
	
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

	enum FileType
	{
		DEL,SEQ,PRG,USR,REL,CBM,DIR
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
	
	protected static int getImageSecsPerTrack(IMAGE_TYPE type, int t)
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

	public static int getImageTotalBytes(IMAGE_TYPE type, int fileSize)
	{
		int ts=getImageNumTracks(type, fileSize);
		int total=0;
		for(int t=1;t<=ts;t++)
			total+=(256*getImageSecsPerTrack(type,t));
		return total;
	}

	public static int getImageDirTrack(IMAGE_TYPE type)
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

	public static short getImageInterleave(IMAGE_TYPE type)
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
	
	public static int getImageDirSector(IMAGE_TYPE type)
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
	
	protected static int getImageNumTracks(IMAGE_TYPE type, long fileSize)
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
	
	protected static char convertToPetscii(byte b)
	{
		if (b < 65)
			return (char) b;
		if (b < 91)
			return Character.toLowerCase((char) b);
		if (b < 192)
			return (char) b;
		if (b < 219)
			return Character.toUpperCase((char) (b - 128));
		return (char) (b - 128);
	}
	
	protected static byte[][][] parseMap(IMAGE_TYPE type, byte[] buf, int fileLength)
	{
		int numTS=getImageNumTracks(type, fileLength);
		byte[][][] tsmap=new byte[numTS+1][getImageSecsPerTrack(type,1)][256];
		int index=0;
		for(int t=1;t<=numTS;t++)
		{
			int secs=getImageSecsPerTrack(type,t);
			for(int s=0;s<secs;s++)
			{
				for(int i=0;i<256;i++)
					tsmap[t][s][i]=buf[index+i];
				index+=256;
			}
		}
		return tsmap;
	}
	
	public static byte[][][] getDisk(IMAGE_TYPE type, File F, int[] fileLen)
	{
		int len=(int)F.length();
		InputStream is=null;
		try
		{
			if(F.getName().toUpperCase().endsWith(".GZ"))
			{
				GzipCompressorInputStream in = new GzipCompressorInputStream(new FileInputStream(F));
				byte[] lbuf = new byte[4096];
				int read=in.read(lbuf);
				ByteArrayOutputStream bout=new ByteArrayOutputStream(len*2);
				while(read >= 0)
				{
					bout.write(lbuf,0,read);
					read=in.read(lbuf);
				}
				in.close();
				len=bout.toByteArray().length;
				is=new ByteArrayInputStream(bout.toByteArray());
			}
			if(len == 0)
			{
				System.err.println(F.getName()+": Error: Failed to read at ALL!");
				return new byte[D64Base.getImageNumTracks(type, len)][255][256];
			}
			byte[] buf=new byte[getImageTotalBytes(type,len)];
			if(is == null)
				is=new FileInputStream(F);
			int totalRead = 0;
			while(totalRead < len)
			{
				int read = is.read(buf,totalRead,buf.length-totalRead);
				if(read>=0)
					totalRead += read;
			}
			is.close();
			if((fileLen != null)&&(fileLen.length>0))
				fileLen[0]=len;
			return parseMap(type,buf,len);
		}
		catch(java.io.IOException e)
		{
			e.printStackTrace(System.err);
			return new byte[D64Base.getImageNumTracks(type, len)][255][256];
		}
	}
	
	public static String toHex(byte b)
	{
		return HEX[unsigned(b)];
	}

	public static String toHex(byte[] buf)
	{
		StringBuffer ret=new StringBuffer("");
		for(int b=0;b<buf.length;b++)
			ret.append(toHex(buf[b]));
		return ret.toString();
	}

	public static short fromHex(String hex)
	{
		return (ANTI_HEX.get(hex)).shortValue();
	}

	public static short unsigned(byte b)
	{
		return (short)(0xFF & b);
	}
	
	public static byte tobyte(int b)
	{
		return (byte)(0xFF & b);
	}
	
	public static byte[] getFileContent(String fileName, byte[][][] tsmap, int t, int mt, int s, List<short[]> secsUsed) throws IOException
	{
		HashSet<byte[]> doneBefore=new HashSet<byte[]>();
		byte[] sector=null;
		ByteArrayOutputStream out=new ByteArrayOutputStream();
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

	public static void finishFillFileList(FileInfo dirInfo, String imgName, int srcFLen, IMAGE_TYPE type, String prefix, byte[][][] tsmap, Set<byte[]> doneBefore, List<FileInfo> finalData, int t, int s, int maxT, boolean readInside)
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
				StringBuffer file=new StringBuffer("");
				for(int x=i+3;x<=fn;x++)
					file.append((char)sector[x]);
				
				if(file.length()>0)
				{
					FileInfo f = new FileInfo();
					f.parentF = dirInfo;
					finalData.add(f);
					
					int pht = unsigned(sector[i+19]);
					int phs = unsigned(sector[i+20]);
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
					short lb=unsigned(sector[i+28]);
					short hb=unsigned(sector[i+29]);
					int size=(256*(lb+(256*hb)));
					if(size<0) 
						System.out.println(lb+","+hb+","+size);
					f.size = size;
					
					try
					{
						int fileT=unsigned(sector[i+1]);
						int fileS=unsigned(sector[i+2]);
						byte[] fileData = null;
						switch(sector[i] & 0x0f)
						{
						case (byte) 0:
							if(readInside)
								fileData =getFileContent(f.fileName,tsmap,fileT,maxT,fileS,f.tracksNSecs);
							f.fileType = FileType.DEL;
							break;
						case (byte) 1:
							if(readInside)
								fileData =getFileContent(f.fileName,tsmap,fileT,maxT,fileS,f.tracksNSecs);
							f.fileType = FileType.SEQ;
							break;
						case (byte) 2:
							if(readInside)
								fileData =getFileContent(f.fileName,tsmap,fileT,maxT,fileS,f.tracksNSecs);
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
								ByteArrayOutputStream data = new ByteArrayOutputStream();
								if(readInside)
								{
									byte[] block = new byte[254];
									byte[] dirLocBlock =tsmap[f.dirLoc[0]][f.dirLoc[1]]; 
									for(int l=2;l<=31;l++)
										block[l-2]=dirLocBlock[f.dirLoc[2]+l-2];
									block[1]=0;
									block[2]=0;
									block[19]=0;
									block[20]=0;
									byte[] convertHeader=new String("PRG formatted GEOS file V1.0").getBytes("US-ASCII");
									for(int l=30;l<30+convertHeader.length;l++)
										block[l]=convertHeader[l-30];
									data.write(block);
									for(int l=0;l<block.length;l++)
										block[l]=f.header[l+2];
									data.write(block);
								}
								byte[] vlirSec = tsmap[fileT][fileS];
								doneBefore.add(vlirSec);
								f.tracksNSecs.add(new short[]{(short)fileT,(short)fileS});
								byte[] vlirblock = new byte[254];
								List<byte[]> contents = new ArrayList<byte[]>();
								for(int vt=0;vt<=254;vt+=2)
								{
									int vfileT=unsigned(vlirSec[vt]);
									int vfileS=unsigned(vlirSec[vt+1]);
									if((vfileT==0)&&(vfileS==255))
									{
										if(vt>1)
										{
											vlirblock[vt-2]=0;
											vlirblock[vt-1]=(byte)(255 & 0xff);
										}
										continue;
									}
									if(readInside)
									{
										byte[] content = getFileContent(f.fileName,tsmap,vfileT,maxT,vfileS,f.tracksNSecs);
										int numBlocks = (int)Math.round(Math.ceil((double)content.length/254.0));
										int lowBlocks = (int)Math.round(Math.floor((double)content.length/254.0));
										int extra = content.length - (lowBlocks * 254) +1;
										vlirblock[vt-2]=(byte)(numBlocks & 0xff);
										vlirblock[vt-1]=(byte)(extra & 0xff);
										for(int x=0;x<numBlocks*254;x+=254)
										{
											byte[] newConBlock = new byte[254];
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
								for(byte[] content : contents)
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
								int recsz = unsigned(sector[i+21]);
								if((pht!=0)
								&&(recsz!=0)
								&&(!doneBefore.contains(tsmap[pht][phs]))
								&&(pht<=maxT))
								{
									byte[] sides=tsmap[pht][phs];
									doneBefore.add(sides);
									f.tracksNSecs.add(new short[]{(short)pht,(short)phs});
									if(unsigned(sides[2])==254)
									{
										if(sides[0]!=0)
											getFileContent(f.fileName,tsmap,unsigned(sides[0]),maxT,unsigned(sides[1]),f.tracksNSecs);
										for(int si=3;si<254;si+=2)
										{
											short sit=unsigned(sides[si]);
											short sis=unsigned(sides[si+1]);
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
										fileData =getFileContent(f.fileName,tsmap,fileT,maxT,fileS,f.tracksNSecs);
								}
							}
							break;
						case (byte) 5:
							f.fileType = FileType.CBM;
							//$FALL-THROUGH$
						case (byte) 6:
							if((sector[i] & 0x0f)==(byte)6)
								f.fileType = FileType.DIR;
							int newDirT=fileT;
							int newDirS=fileS;
							//if(flags.contains(COMP_FLAG.RECURSE))
							fillFileListFromHeader(imgName,srcFLen,type,f.filePath+"/",tsmap,doneBefore,finalData,newDirT,newDirS,maxT,f, readInside);
							if(readInside)
								fileData=getFileContent(f.fileName,tsmap,newDirT,maxT,newDirS,f.tracksNSecs);
							//finalData.remove(f);
							break;
						default:
							f.fileType = FileType.PRG;
							if(readInside)
								fileData =getFileContent(f.fileName,tsmap,fileT,maxT,fileS,f.tracksNSecs);
							break;
						}
						if(fileData==null)
						{
							System.err.println(imgName+": Error reading: "+f.fileName+": "+fileT+","+fileS);
							return;
						}
						else
							f.data = fileData;
					}
					catch(IOException e)
					{
						System.err.println(imgName+": Error: "+f.filePath+": "+e.getMessage());
						return;
					}
				}
			}
			t=unsigned(sector[0]);
			s=unsigned(sector[1]);
		}
	}
	
	protected static boolean isSectorAllocated(final byte[][][] diskBytes, IMAGE_TYPE imagetype, int imageFLen, final short track, final short sector) throws IOException
	{
		final boolean[] isAllocated = new boolean[]{false};
		D64Mod.bamPeruse(diskBytes, imagetype, imageFLen, new BAMBack(){
			@Override
			public boolean call(int t, int s, boolean set,
					short[] curBAM, short bamByteOffset,
					short sumBamByteOffset, short bamMask) 
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

	public static void fillFileListFromHeader(String imgName, int srcFLen, IMAGE_TYPE type, String prefix, byte[][][] tsmap, Set<byte[]> doneBefore, List<FileInfo> finalData, int t, int s, int maxT, FileInfo f, boolean readInside) throws IOException
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
			short possDTrack = unsigned(sector[160+11]);
			short possDSector = unsigned(sector[160+12]);
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

	protected static short[] getBAMStart(IMAGE_TYPE type)
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
	
	protected static short[] getBAMNext(short[] prev, IMAGE_TYPE type)
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
	
	protected static void bamPeruse(byte[][][] bytes, IMAGE_TYPE type, long imageSize, BAMBack call) throws IOException
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
			int secsPerTrack = getImageSecsPerTrack(type,t);
			int skipByte = currBAM[4];
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
				boolean set = (bamByte & mask) == mask;
				if((call != null)&&(call.call(t, s, set, currBAM, bamByteOffset, sumBamByteOffset, mask)))
					return;
			}
			if(currBAM[5] != 0)
				bamOffset += currBAM[5];
			else
				bamOffset+=(skipByte + (int)Math.round(Math.ceil(secsPerTrack/8.0)));
		}
	}
	
	public static List<FileInfo> getDiskFiles(String imgName, IMAGE_TYPE type, byte[][][] tsmap, int fileSize)
	{
		int t=getImageDirTrack(type);
		int maxT=getImageNumTracks(type, fileSize);
		int s=getImageDirSector(type);
		List<FileInfo> finalData=new Vector<FileInfo>();
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
		Set<byte[]> doneBefore=new HashSet<byte[]>();
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
		catch(IOException e)
		{
			System.err.println(imgName+": disk Dir Error: "+e.getMessage());
		}
		switch(type)
		{
		case D71:
			for(int sec=1;sec<getImageSecsPerTrack(type, 53);sec++)
			{
				boolean found=false;
				for(short[] chk : f.tracksNSecs)
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

	protected static IMAGE_TYPE getImageType(File F)
	{
		for(IMAGE_TYPE img : IMAGE_TYPE.values())
		{
			if(F.getName().toUpperCase().endsWith(img.toString()))
			{
				IMAGE_TYPE type=img;
				return type;
			}
		}
		return null;
	}
	
	protected static IMAGE_TYPE getImageTypeAndZipped(File F)
	{
		for(IMAGE_TYPE img : IMAGE_TYPE.values())
		{
			if(F.getName().toUpperCase().endsWith(img.toString())
			||F.getName().toUpperCase().endsWith(img.toString()+".GZ"))
			{
				IMAGE_TYPE type=img;
				return type;
			}
		}
		return null;
	}
}
