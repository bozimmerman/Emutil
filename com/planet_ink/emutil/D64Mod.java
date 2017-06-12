package com.planet_ink.emutil;
import java.io.*;
import java.util.*;
import java.util.regex.Pattern;
/* 
Copyright 2017-2017 Bo Zimmerman

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
public class D64Mod 
{
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
	
	public static class FileInfo {
		String fileName = "";
		String filePath = "";
		FileType fileType = null;
		int size = 0;
		byte[] data = null;
		byte[] header = null;
		short[] dirLoc = new short[3]; 
		List<short[]> tracksNSecs = new ArrayList<short[]>();
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
	
	private static short[] getBAMStart(IMAGE_TYPE type)
	{
		switch(type)
		{
		case D64:
			return new short[]{18,0,4,143};
		case D71:
			return new short[]{18,0,4,143};
		case D81:
			return new short[]{40,1,16,255};
		case D80:
			return new short[]{38,0,6,255};
		case D82:
			return new short[]{38,0,6,255};
		case DNP:
			return new short[]{1,2,32,255};
		}
		return null;
	}
	
	private static short[] getBAMNext(short[] prev, IMAGE_TYPE type)
	{
		switch(type)
		{
		case D64:
			return null;
		case D71:
			if(prev[0]==18)
				return new short[]{53,0,0,104};
			return null;
		case D81:
			if((prev[0]==40)&&(prev[1]==1))
				return new short[]{40,2,16,255};
			return null;
		case D80:
			if((prev[0]==38)&&(prev[1]==0))
				return new short[]{38,3,6,140};
			return null;
		case D82:
			if((prev[0]==38)&&(prev[1]==0))
				return new short[]{38,3,6,255};
			if((prev[0]==38)&&(prev[1]==3))
				return new short[]{38,6,6,255};
			if((prev[0]==38)&&(prev[1]==6))
				return new short[]{38,9,6,255};
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
	
	private static boolean bamCheck(byte[][][] bytes, IMAGE_TYPE type, short[] ts, long imageSize) throws IOException
	{
		short[] currBAM = getBAMStart(type);
		byte[] bam=bytes[currBAM[0]-1][currBAM[1]];
		short bamOffset = currBAM[2];
		for(int t=1;t<getImageNumTracks(type, imageSize);t++)
		{
			if(bamOffset > currBAM[2])
			{
				currBAM = getBAMNext(currBAM, type);
				if(currBAM==null)
					throw new IOException("BAM failure, no sector found: "+ts[0]+","+ts[1]);
				bam=bytes[currBAM[0]-1][currBAM[1]];
				bamOffset = currBAM[2];
			}
			int secsPerTrack = getImageSecsPerTrack(type,t);
			if(bam[bamOffset]!=secsPerTrack)
				throw new IOException("BAM failure at "+currBAM[0]+","+currBAM[1]+"@"+currBAM[2]);
			for(int s=0;s<secsPerTrack;s++)
			{
				byte bamByte = bam[bamOffset + 1 + (int)Math.round(Math.ceil(s/8.0))];
				short mask = (short)Math.round(Math.pow(2.0,s%8));
				boolean set = (bamByte & mask) == mask;
				if((ts[0]==t)&&(ts[1]==s))
					return set;
			}
			bamOffset+=(1 + (int)Math.round(Math.ceil(secsPerTrack/8.0)));
		}
		throw new IOException("BAM failure, no sector found: "+ts[0]+","+ts[1]);
	}
	
	private static void ballocate(byte[][][] bytes, IMAGE_TYPE type, short[] ts)
	{
		
	}
	
	private static int getImageSecsPerTrack(IMAGE_TYPE type, int t)
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
	
	private static int getImageNumTracks(IMAGE_TYPE type, long fileSize)
	{
		switch(type)
		{
		case D64:
			return 35;
		case D71:
			return 70;
		case D81:
			return 79;
		case D80:
			return 77;
		case D82:
			return 2*77;
		case DNP:
			return (int)(fileSize / 256 / 256);
		}
		return -1;
	}
	
	private static char convertToPetscii(byte b)
	{
		if(b<65) return (char)b;
		if(b<91) return Character.toLowerCase((char)b);
		if(b<192) return (char)b;
		if(b<219) return Character.toUpperCase((char)(b-128));
		return (char)(b-128);
	}
	
	private static byte[][][] parseMap(IMAGE_TYPE type, byte[] buf, int fileLength)
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
	
	public static byte[][][] getDisk(IMAGE_TYPE type, File F)
	{
		byte[] buf=new byte[getImageTotalBytes(type,(int)F.length())];
		try
		{
			final FileInputStream is=new FileInputStream(F);
			int i=0;
			while(i<buf.length)
			{
				int x=is.read();
				buf[i++]=(byte)x;
			}
			is.close();
		}
		catch(java.io.IOException e)
		{
			e.printStackTrace(System.err);
			System.exit(-1);
		}
		return parseMap(type,buf,(int)F.length());
	}
	
	public static String toHex(byte b){ return HEX[unsigned(b)];}
	public static String toHex(byte[] buf){
		StringBuffer ret=new StringBuffer("");
		for(int b=0;b<buf.length;b++)
			ret.append(toHex(buf[b]));
		return ret.toString();
	}

	public static short fromHex(String hex)
	{
		return (ANTI_HEX.get(hex)).shortValue();
	}

	public static byte[] getFileContent(byte[][][] tsmap, int t, int mt, int s, List<short[]> secsUsed) throws IOException
	{
		HashSet<byte[]> doneBefore=new HashSet<byte[]>();
		byte[] sector=null;
		ByteArrayOutputStream out=new ByteArrayOutputStream();
		if(t>=tsmap.length)
			throw new IOException("Illegal Track "+t);
		if(s>=tsmap[t].length)
			throw new IOException("Illegal Sector ("+t+","+s+")");
		while((t!=0)&&(!doneBefore.contains(tsmap[t][s]))&&(t<=mt))
		{
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

	public static short unsigned(byte b)
	{
		return (short)(0xFF & b);
	}
	
	public static void finishFillFiledata(File srcF, IMAGE_TYPE type, String prefix, byte[][][] tsmap, Set<byte[]> doneBefore, List<FileInfo> finalData, int t, int s, int maxT)
	{
		byte[] sector;
		while((t!=0)&&(t<tsmap.length)&&(s<tsmap[t].length)&&(!doneBefore.contains(tsmap[t][s]))&&(t<=maxT))
		{
			sector=tsmap[t][s];
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
					finalData.add(f);
					
					int pht = unsigned(sector[i+19]);
					int phs = unsigned(sector[i+20]);
					if(((sector[i] & 0x0f)!=4) //rel files never have headers
					&&(pht!=0)
					&&(pht<=maxT))
					{
						final byte[] ssec = tsmap[pht][phs];
						if((unsigned(ssec[0])==0)&&(unsigned(ssec[1])==255)&&(!doneBefore.contains(ssec)))
						{
							f.header = ssec;
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
						byte[] fileData;
						switch(sector[i] & 0x0f)
						{
						case (byte) 0:
							fileData =getFileContent(tsmap,fileT,maxT,fileS,f.tracksNSecs);
							f.fileType = FileType.DEL;
							break;
						case (byte) 1:
							fileData =getFileContent(tsmap,fileT,maxT,fileS,f.tracksNSecs);
							f.fileType = FileType.SEQ;
							break;
						case (byte) 2:
							fileData =getFileContent(tsmap,fileT,maxT,fileS,f.tracksNSecs);
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
								byte[] vlirSec = tsmap[fileT][fileS];
								doneBefore.add(vlirSec);
								f.tracksNSecs.add(new short[]{(short)fileT,(short)fileS});
								for(int vt=0;vt<=254;vt+=2)
								{
									int vfileT=unsigned(vlirSec[vt]);
									int vfileS=unsigned(vlirSec[vt+1]);
									if((vfileT==0)&&(vfileS==255))
										continue;
									data.write(getFileContent(tsmap,vfileT,maxT,vfileS,f.tracksNSecs));
								}
								fileData = data.toByteArray();
							}
							else
								fileData =getFileContent(tsmap,fileT,maxT,fileS,f.tracksNSecs);
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
										getFileContent(tsmap,unsigned(sides[0]),maxT,unsigned(sides[1]),f.tracksNSecs);
									else
									if(unsigned(sides[3])==recsz)
										getFileContent(tsmap,unsigned(sides[0]),maxT,unsigned(sides[1]),f.tracksNSecs);
									fileData =getFileContent(tsmap,fileT,maxT,fileS,f.tracksNSecs);
								}
								else
									fileData = null;
							}
							break;
						case (byte) 5:
							f.fileType = FileType.CBM;
						case (byte) 6:
							f.fileType = FileType.DIR;
							int newDirT=fileT;
							int newDirS=fileS;
							//if(flags.contains(COMP_FLAG.RECURSE))
							fillFiledata(srcF,type,f.filePath+"/",tsmap,doneBefore,finalData,newDirT,newDirS,maxT,f);
							fileData=getFileContent(tsmap,newDirT,maxT,newDirS,f.tracksNSecs);
							finalData.remove(f);
							break;
						default:
							f.fileType = FileType.PRG;
							fileData =getFileContent(tsmap,fileT,maxT,fileS,f.tracksNSecs);
							break;
						}
						if(fileData==null)
						{
							System.err.println(srcF.getName()+": Error reading: "+f.fileName+": "+fileT+","+fileS);
							return;
						}
						else
							f.data = fileData;
					}
					catch(IOException e)
					{
						System.err.println(srcF.getName()+": Error: "+f.filePath+": "+e.getMessage());
						return;
					}
				}
			}
			t=unsigned(sector[0]);
			s=unsigned(sector[1]);
		}
	}
	
	public static void fillFiledata(File srcF, IMAGE_TYPE type, String prefix, byte[][][] tsmap, Set<byte[]> doneBefore, List<FileInfo> finalData, int t, int s, int maxT, FileInfo f) throws IOException
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
			int possDTrack = unsigned(sector[160+11]);
			int possDSector = unsigned(sector[160+12]);
			if((possDTrack!=0)
			&&(possDTrack<tsmap.length)
			&&(possDSector<tsmap[possDTrack].length)
			&&(!doneBefore.contains(tsmap[possDTrack][possDSector]))
			&&(possDTrack<=maxT))
			{
				finishFillFiledata(srcF,type,prefix+"*/",tsmap,doneBefore,finalData,possDTrack,possDSector,maxT);
				getFileContent(tsmap,possDTrack,maxT,possDSector,(f!=null)?f.tracksNSecs:null); // fini
			}
		}
		finishFillFiledata(srcF,type,prefix,tsmap,doneBefore,finalData,t,s,maxT);
	}

	
	public static List<FileInfo> getDiskFiles(File srcF, IMAGE_TYPE type, byte[][][] tsmap, long fileSize)
	{
		int t=getImageDirTrack(type);
		int maxT=D64Mod.getImageNumTracks(type, fileSize);
		int s=getImageDirSector(type);
		List<FileInfo> finalData=new Vector<FileInfo>();
		Set<byte[]> doneBefore=new HashSet<byte[]>();
		try
		{
			D64Mod.fillFiledata(srcF, type,"",tsmap, doneBefore, finalData, t, s, maxT,null);
		}
		catch(IOException e)
		{
			System.err.println("Disk Dir Error: "+e.getMessage());
		}
		return finalData;
	}

	private static IMAGE_TYPE getImageType(File F)
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
	
	enum Action
	{
		SCRATCH, EXTRACT, INSERT
	}
	
	public static void main(String[] args)
	{
		if(args.length<3)
		{
			System.out.println("D64Mod v1.0 (c)2017-2017 Bo Zimmerman");
			System.out.println("");
			System.out.println("USAGE: ");
			System.out.println("  D64Mod <image file> <action> <action arguments>");
			System.out.println("ACTIONS:");
			System.out.println("  SCRATCH <file>");
			System.out.println("  EXTRACT <file> <target path>");
			System.out.println("  INSERT <source path> <file>");
			System.out.println("");
			return;
		}
		String imagePath=args[0];
		String actionStr=args[1];
		String imageFileStr = args[2];
		String expr="";
		expr=expr.trim();
		File imageF=new File(imagePath);
		if((!imageF.isFile())||(!imageF.exists())||(!imageF.canRead()))
		{
			System.err.println("image not found: "+imagePath);
			System.exit(-1);
		}
		IMAGE_TYPE imagetype = getImageType(imageF);
		if(imagetype == null)
		{
			System.err.println("File is not an image: "+imagePath);
			System.exit(-1);
		}
		Action action = Action.EXTRACT;
		String localFileStr="";
		try
		{
			action=Action.valueOf(actionStr);
			switch(action)
			{
			case SCRATCH:
				break;
			case INSERT:
				if(args.length<4)
				{
					System.err.println("Missing target file");
					System.exit(-1);
				}
				localFileStr = args[2];
				imageFileStr = args[3];
				break;
			case EXTRACT:
				if(args.length<4)
				{
					System.err.println("Missing target file");
					System.exit(-1);
				}
				localFileStr = args[3];
				break;
			}
		}
		catch(Exception e)
		{
			System.err.println("Invalid action: "+actionStr);
			System.exit(-1);
		}
		final byte[][][] diskBytes = getDisk(imagetype,imageF);
		final List<FileInfo> files = getDiskFiles(imageF, imagetype, diskBytes, imageF.length());
		FileInfo file = null;
		for(FileInfo f : files)
		{
			if(f.filePath.equalsIgnoreCase(imageFileStr))
			{
				file = f;
				break;
			}
		}
		if((action == Action.INSERT) && (file != null))
		{
			System.err.println("File exists in image: "+imageFileStr);
			System.exit(-1);
		}
		else
		if(file == null)
		{
			System.err.println("File not found in image: "+imageFileStr);
			System.exit(-1);
		}
		boolean rewriteD64 = false;
		switch(action)
		{
		case SCRATCH:
		{
			break;
		}
		case EXTRACT:
		{
			File localFileF=new File(localFileStr);
			try {
				FileOutputStream fout=new FileOutputStream(localFileF);
				fout.write(file.data);
				fout.close();
				System.out.println(file.data.length+" bytes written to "+localFileF.getAbsolutePath());
			} catch (Exception e) {
				System.err.println(e.getMessage());
				System.exit(-1);
			}
			break;
		}
		case INSERT:
		{
			int x=localFileStr.lastIndexOf('.');
			File localFileF;
			if(x>=0)
			{
				String ext=localFileStr.substring(x+1);
				FileType poss;
				try
				{ 
					poss = FileType.valueOf(ext.toUpperCase().trim()); 
					localFileF=new File(localFileStr);
					if(!localFileF.exists())
						localFileF=new File(localFileStr.substring(0,x));
				} 
				catch(Exception e)
				{
					poss = FileType.PRG;
					localFileF=new File(localFileStr);
				}
			}
			else
				localFileF= new File(localFileStr);
			if(!localFileF.exists())
			{
				System.err.println("File not found: "+localFileStr);
				System.exit(-1);
			}
			byte[] fileData = new byte[(int)localFileF.length()];
			try
			{
				FileInputStream fin = new FileInputStream(localFileF);
				int totalBytesRead = 0;
				while(totalBytesRead < localFileF.length())
				{
					int bytesRead = fin.read(fileData,totalBytesRead,(int)(localFileF.length()-totalBytesRead));
					if(bytesRead >=0)
						totalBytesRead+=bytesRead;
				}
				fin.close();
			} catch (Exception e) {
				System.err.println(e.getMessage());
				System.exit(-1);
			}
			//TODO:
			break;
		}
		}
		if(rewriteD64)
		{
			try
			{
				FileOutputStream fout = new FileOutputStream(imageF);
				for(int b1=0;b1<diskBytes.length;b1++)
				{
					for(int b2=0;b2<diskBytes[b1].length;b2++)
					{
						fout.write(diskBytes[b1][b2]);
					}
				}
				fout.close();
			} catch (Exception e) {
				System.err.println(e.getMessage());
				System.exit(-1);
			}
		}
		System.exit(0);
	}
}
