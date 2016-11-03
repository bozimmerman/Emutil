package com.planet_ink.emutil;
import java.io.*;
import java.util.*;
/* 
Copyright 2016-2016 Bo Zimmerman

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
public class D64Compare 
{
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

	public static class FileInfo {
		String fileName = "";
		String filePath = "";
		String fileType = "";
		int size = 0;
		byte[] data = null;
		byte[] header = null;
	}
	
	public enum COMP_FLAG {
		VERBOSE,
		RECURSE,
	};
	public enum FILE_FORMAT {
		PETSCII,
		ASCII,
		HEX;
	};
	public static final String[] HEX=new String[256];
	public static final Hashtable<String,Short> ANTI_HEX=new Hashtable<String,Short>();
	public static final String HEX_DIG="0123456789ABCDEF";
	static{
		for(int h=0;h<16;h++)
			for(int h2=0;h2<16;h2++)
			{
				HEX[(h*16)+h2]=""+HEX_DIG.charAt(h)+HEX_DIG.charAt(h2);
				ANTI_HEX.put(HEX[(h*16)+h2],new Short((short)((h*16)+h2)));
			}
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

	public static byte[] getFileContent(byte[][][] tsmap, int t, int mt, int s)
	{
		HashSet<byte[]> doneBefore=new HashSet<byte[]>();
		byte[] sector=null;
		ByteArrayOutputStream out=new ByteArrayOutputStream();
		try
		{
			while((t!=0)&&(!doneBefore.contains(tsmap[t][s]))&&(t<=mt))
			{
				int maxBytes=255;
				sector=tsmap[t][s];
				if(sector[0]==0) maxBytes=unsigned(sector[1]);
				doneBefore.add(sector);
				for(int i=2;i<=maxBytes;i++)
					out.write(sector[i]);
				t=unsigned(sector[0]);
				s=unsigned(sector[1]);
			}
			return out.toByteArray();
		}
		catch(Throwable th)
		{
			th.printStackTrace(System.err);
			return null;
		}
	}

	public static short unsigned(byte b)
	{
		return (short)(0xFF & b);
	}
	
	public static void finishFillFiledata(IMAGE_TYPE type, String prefix, byte[][][] tsmap, Set<byte[]> doneBefore, List<FileInfo> finalData, int t, int s, int maxT)
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
					&&(pht<=maxT)) //TODO: check geos?
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
					short lb=unsigned(sector[i+28]);
					short hb=unsigned(sector[i+29]);
					int size=(256*(lb+(256*hb)));
					if(size<0) 
						System.out.println(lb+","+hb+","+size);
					f.size = size;
					
					int fileT=unsigned(sector[i+1]);
					int fileS=unsigned(sector[i+2]);
					byte[] fileData;
					switch(sector[i] & 0x0f)
					{
					case (byte) 0:
						fileData =getFileContent(tsmap,fileT,maxT,fileS);
						f.fileType = ("del");
						break;
					case (byte) 1:
						fileData =getFileContent(tsmap,fileT,maxT,fileS);
						f.fileType = ("seq");
						break;
					case (byte) 2:
						fileData =getFileContent(tsmap,fileT,maxT,fileS);
						f.fileType = ("prg");
						break;
					case (byte) 3:
						f.fileType = ("usr");
						//fileData =getFileContent(tsmap,fileT,maxT,fileS);
						if((unsigned(sector[i+21])==1)
						&&(f.header!=null)
						&&(fileT!=0)
						&&(!doneBefore.contains(tsmap[fileT][fileS]))&&(fileT<=maxT))
						{
							ByteArrayOutputStream data = new ByteArrayOutputStream();
							byte[] vlirSec = tsmap[fileT][fileS];
							doneBefore.add(vlirSec);
							for(int vt=0;vt<=254;vt+=2)
							{
								int vfileT=unsigned(vlirSec[vt]);
								int vfileS=unsigned(vlirSec[vt+1]);
								if((vfileT==0)&&(vfileS==0))
									break;
								if((vfileT>0)&&(vfileS!=255))
								{
									try
									{
										data.write(getFileContent(tsmap,vfileT,maxT,vfileS));
									}
									catch(Exception e)
									{}
								}
							}
							fileData = data.toByteArray();
						}
						else
							fileData =getFileContent(tsmap,fileT,maxT,fileS);
						break;
					case (byte) 4:
						f.fileType = ("rel");
						{
							int recsz = unsigned(sector[i+21]);
							if((pht!=0)
							&&(recsz!=0)
							&&(!doneBefore.contains(tsmap[pht][phs]))
							&&(pht<=maxT))
							{
								byte[] sides=tsmap[pht][phs];
								doneBefore.add(sides);
								if(unsigned(sides[2])==254)
								{
									getFileContent(tsmap,unsigned(sides[0]),maxT,unsigned(sides[1]));
								}
								else
								if(unsigned(sides[3])==recsz)
								{
									getFileContent(tsmap,unsigned(sides[0]),maxT,unsigned(sides[1]));
								}
								fileData =getFileContent(tsmap,fileT,maxT,fileS);
							}
							else
								fileData = null;
						}
						break;
					case (byte) 5:
						f.fileType = ("cbm");
						fileData =getFileContent(tsmap,fileT,maxT,fileS);
						break;
					case (byte) 6:
						f.fileType = ("dir");
						int newDirT=fileT;
						int newDirS=fileS;
						fillFiledata(type,f.filePath+"/",tsmap,doneBefore,finalData,newDirT,newDirS,maxT);
						fileData=getFileContent(tsmap,newDirT,maxT,newDirS);
						finalData.remove(f);
						break;
					default:
						f.fileType = ("?");
						fileData =getFileContent(tsmap,fileT,maxT,fileS);
						break;
					}
					if(fileData==null)
					{
						System.err.println("Error reading: "+fileT+","+fileS);
						return;
					}
					else
						f.data = fileData;
				}
			}
			t=unsigned(sector[0]);
			s=unsigned(sector[1]);
		}
	}
	
	public static void fillFiledata(IMAGE_TYPE type, String prefix, byte[][][] tsmap, Set<byte[]> doneBefore, List<FileInfo> finalData, int t, int s, int maxT)
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
				finishFillFiledata(type,prefix+"*/",tsmap,doneBefore,finalData,possDTrack,possDSector,maxT);
				getFileContent(tsmap,possDTrack,maxT,possDSector);
			}
		}
		finishFillFiledata(type,prefix,tsmap,doneBefore,finalData,t,s,maxT);
	}

	
	public static List<FileInfo> getFiledata(IMAGE_TYPE type, byte[][][] tsmap, Set<COMP_FLAG> flags, long fileSize)
	{
		int t=getImageDirTrack(type);
		int maxT=D64Compare.getImageNumTracks(type, fileSize);
		int s=getImageDirSector(type);
		List<FileInfo> finalData=new Vector<FileInfo>();
		Set<byte[]> doneBefore=new HashSet<byte[]>();
		D64Compare.fillFiledata(type,"",tsmap, doneBefore, finalData, t, s, maxT);
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
	
	public static void main(String[] args)
	{
		if(args.length<2)
		{
			System.out.println("D64Compare v1.0 (c)2016-2016 Bo Zimmerman");
			System.out.println("");
			System.out.println("USAGE: ");
			System.out.println("  D64Compare <options> <file1> <file2>");
			System.out.println("OPTIONS:");
			System.out.println("  -R recursive search inside DNP");
			System.out.println("  -V verbose");
			System.out.println("");
			return;
		}
		HashSet<COMP_FLAG> flags=new HashSet<COMP_FLAG>();
		String path=null;
		String expr="";
		for(int i=0;i<args.length;i++)
		{
			if((args[i].startsWith("-")&&(path==null)))
			{
				for(int c=1;c<args[i].length();c++)
				{
					switch(args[i].charAt(c))
					{
					case 'r':
					case 'R': flags.add(COMP_FLAG.RECURSE); break;
					case 'v':
					case 'V': flags.add(COMP_FLAG.VERBOSE); break;
					}
				}
			}
			else
			if(path==null)
				path=args[i];
			else
				expr+=" "+args[i];
		}
		expr=expr.trim();
		if(path==null)
		{
			System.err.println("Path1 not found!");
			System.exit(-1);
		}
		File F1=new File(path);
		File F2=new File(expr);
		if((!F1.exists())||F1.isDirectory())
		{
			System.err.println("File1 not found!");
			System.exit(-1);
		}
		if((!F2.exists())||F2.isDirectory())
		{
			System.err.println("File2 not found!");
			System.exit(-1);
		}
		
		IMAGE_TYPE typeF1 = getImageType(F1);
		if(typeF1==null)
		{
			System.err.println("Error reading :"+F1.getName());
			System.exit(-1);
		}
		byte[][][] diskF1=getDisk(typeF1,F1);
		List<FileInfo> fileData1=getFiledata(typeF1,diskF1,flags,F1.length());
		if(fileData1==null)
		{
			System.err.println("Bad extension :"+F1.getName());
			System.exit(-1);
		}
		diskF1=null;
		
		IMAGE_TYPE typeF2 = getImageType(F2);
		if(typeF2==null)
		{
			System.err.println("Error reading :"+F2.getName());
			System.exit(-1);
		}
		byte[][][] diskF2=getDisk(typeF2,F2);
		List<FileInfo> fileData2=getFiledata(typeF2,diskF2,flags,F2.length());
		if(fileData2==null)
		{
			System.err.println("Bad extension :"+F2.getName());
			System.exit(-1);
		}
		diskF2=null;
		List<FileInfo> missingFromDisk2 = new LinkedList<FileInfo>();
		missingFromDisk2.addAll(fileData1);
		List<FileInfo[]> foundButNotEqual = new LinkedList<FileInfo[]>();
		for(FileInfo f1 : fileData1)
		{
			for(FileInfo f2 : fileData2)
			{
				if(f1.filePath.equals(f2.filePath))
				{
					missingFromDisk2.remove(f1);
					if(!Arrays.equals(f1.data, f2.data))
						foundButNotEqual.add(new FileInfo[]{f1,f2});
				}
			}
		}
		List<FileInfo> missingFromDisk1 = new LinkedList<FileInfo>();
		missingFromDisk1.addAll(fileData2);
		for(FileInfo f2 : fileData2)
		{
			for(FileInfo f1 : fileData1)
			{
				if(f2.filePath.equals(f1.filePath))
				{
					missingFromDisk1.remove(f2);
					if(!Arrays.equals(f1.data, f2.data))
						foundButNotEqual.add(new FileInfo[]{f1,f2});
				}
			}
		}
		List<FileInfo[]> wrongPathFromDisks = new LinkedList<FileInfo[]>();
		for(FileInfo f2 : fileData2)
		{
			for(Iterator<FileInfo> i=missingFromDisk2.iterator();i.hasNext();)
			{
				FileInfo f1=i.next();
				if(f1.fileName.equalsIgnoreCase(f2.fileName))
				{
					i.remove();
					wrongPathFromDisks.add(new FileInfo[]{f1,f2});
					if(!Arrays.equals(f1.data, f2.data))
						foundButNotEqual.add(new FileInfo[]{f1,f2});
				}
			}
		}
		for(FileInfo f1 : fileData1)
		{
			for(Iterator<FileInfo> i=missingFromDisk1.iterator();i.hasNext();)
			{
				FileInfo f2=i.next();
				if(f2.fileName.equalsIgnoreCase(f1.fileName))
				{
					i.remove();
					wrongPathFromDisks.add(new FileInfo[]{f2,f1});
					if(!Arrays.equals(f1.data, f2.data))
						foundButNotEqual.add(new FileInfo[]{f1,f2});
				}
			}
		}
		System.out.println("");
		if(missingFromDisk1.size()>0)
		{
			System.out.println("Found in disk 1, but missing from disk 2:");
			for(FileInfo f : missingFromDisk1)
				System.out.println(f.filePath+"("+f.fileType+"): "+f.size+" bytes.");
			System.out.println("");
		}
		if(missingFromDisk2.size()>0)
		{
			System.out.println("Found in disk 2, but missing from disk 1:");
			for(FileInfo f : missingFromDisk2)
				System.out.println(f.filePath+"("+f.fileType+"): "+f.size+" bytes.");
			System.out.println("");
		}
		if(wrongPathFromDisks.size()>0)
		{
			System.out.println("Found in one disk, but at a different path from the other disk:");
			for(FileInfo[] f : wrongPathFromDisks)
				System.out.println(f[0].filePath+"("+f[0].fileType+"): "+f[0].size+" bytes, versus, "+f[1].filePath+"("+f[1].fileType+"): "+f[1].size+" bytes");
			System.out.println("");
		}
		if(foundButNotEqual.size()>0)
		{
			System.out.println("Found, but with different data:");
			for(FileInfo[] f : foundButNotEqual)
				System.out.println(f[0].filePath+"("+f[0].fileType+"): "+f[0].size+" bytes, versus, "+f[1].filePath+"("+f[1].fileType+"): "+f[1].size+" bytes");
			System.out.println("");
		}
	}
}
