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
		D64 { public String toString() { return ".D64";}},
		D71 { public String toString() { return ".D71";}},
		D81 { public String toString() { return ".D81";}},
		D80 { public String toString() { return ".D80";}},
		D82 { public String toString() { return ".D82";}},
		DNP { public String toString() { return ".DNP";}},
	};

	public static class FileInfo {
		String fileName = "";
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
			return 1;
		case D71:
			return 1;
		case D81:
			return 3;
		case D80:
		case D82:
			return 1;
		case DNP:
			return 1;
		}
		return -1;
	}
	
	private static int getImageNumTracks(IMAGE_TYPE type, int fileSize)
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
			return fileSize / 256 / 256;
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
	public static short fromHex(String hex){ return ((Short)ANTI_HEX.get(hex)).shortValue();}
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
	
	public static Vector<FileInfo> getFiledata(IMAGE_TYPE type, byte[][][] tsmap, HashSet<COMP_FLAG> flags, int fileSize)
	{
		int t=getImageDirTrack(type);
		int maxT=D64Compare.getImageNumTracks(type, fileSize);
		int s=getImageDirSector(type);
		Vector<FileInfo> finalData=new Vector<FileInfo>();
		//Vector<String> types=flags.contains(SEARCH_FLAG.VERBOSE)?new Vector<String>():null;
		//Vector<String> sizes=flags.contains(SEARCH_FLAG.VERBOSE)?new Vector<String>():null;
		//Vector<byte[]> data=(inside||md5)?new Vector<byte[]>():null;
		byte[] sector=tsmap[t][s];
		HashSet<byte[]> doneBefore=new HashSet<byte[]>();
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
					if((sector[fn]!=-96)&&(sector[fn]!=0))
						break;
				StringBuffer file=new StringBuffer("");
				for(int x=i+3;x<=fn;x++)
					file.append(toHex(sector[x]));
					
				if(file.length()>0)
				{
					FileInfo f = new FileInfo();
					finalData.add(f);
					f.fileName=file.toString();
					if(flags.contains(COMP_FLAG.VERBOSE))
					{
						short lb=unsigned(sector[i+28]);
						short hb=unsigned(sector[i+29]);
						int size=(256*(lb+(256*hb)));
						if(size<0) System.out.println(lb+","+hb+","+size);
						f.size = size;
						
						switch(sector[i])
						{
						case (byte)129: f.fileType=("seq"); break;
						case (byte)130: f.fileType=("prg"); break;
						case (byte)131: f.fileType=("usr"); break;
						case (byte)132: f.fileType=("rel"); break;
						default:f.fileType=("?");break;
						}
					}
					int fileT=unsigned(sector[i+1]);
					int fileS=unsigned(sector[i+2]);
					byte[] fileData =getFileContent(tsmap,fileT,maxT,fileS);
					if(fileData==null)
					{
						System.err.println("Error reading: "+fileT+","+fileS);
						return null;
					}
					else
						f.data = fileData;
				}
			}
			t=unsigned(sector[0]);
			s=unsigned(sector[1]);
		}
		return finalData;
	}

	private static void compare(File F1, File F2, HashSet<COMP_FLAG> flags)
	{
		File F=F1;
		for(IMAGE_TYPE img : IMAGE_TYPE.values())
			if(F.getName().toUpperCase().endsWith(img.toString()))
			{
				IMAGE_TYPE type=img;
				byte[][][] disk=getDisk(type,F);
				Vector<FileInfo> fileData=getFiledata(type,disk,flags,(int)F.length());
				if(fileData==null)
				{
					System.err.println("Error reading :"+F.getName());
					continue;
				}
				for(int n=0;n<fileData.size();n++)
				{
					FileInfo f = fileData.elementAt(n);
					String name=f.fileName;
					StringBuffer asciiName=new StringBuffer("");
					for(int x=0;x<name.length();x++)
						asciiName.append(D64Compare.convertToPetscii((byte)name.charAt(x)));
					System.out.print("  "+asciiName.toString());
					if((flags.contains(COMP_FLAG.VERBOSE)))
						System.out.print(","+f.fileType+", "+f.size+" bytes");
					System.out.println("");
				}
				break;
			}
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
		compare(F1,F2,flags);
	}
}
