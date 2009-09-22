package com.planet_ink.emutil;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
/* 
Copyright 2000-2006 Bo Zimmerman

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/public class D64Search 
{
	// todo: add file masks to options
	public enum IMAGE_TYPE {
		D64 { public String toString() { return ".D64";}},
		D71 { public String toString() { return ".D71";}},
		D81 { public String toString() { return ".D81";}},
		D80 { public String toString() { return ".D80";}},
		D82 { public String toString() { return ".D82";}},
	};

	public enum SEARCH_FLAG {
		CASESENSITIVE,
		VERBOSE,
		RECURSE,
		INSIDE,
		SHOWMD5;
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
		if(type.equals(IMAGE_TYPE.D64))
		{
			if(t<18) return 21;
			if(t<25) return 19;
			if(t<31) return 18;
			return 17;
		}
		else
		if(type.equals(IMAGE_TYPE.D71))
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
		else
		if(type.equals(IMAGE_TYPE.D81))
			return 40;
		else
		if(type.equals(IMAGE_TYPE.D80)||type.equals(IMAGE_TYPE.D82))
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
		return -1;
	}

	public static int getImageTotalBytes(IMAGE_TYPE type)
	{
		int ts=getImageNumTracks(type);
		int total=0;
		for(int t=1;t<=ts;t++)
			total+=(256*getImageSecsPerTrack(type,t));
		return total;
	}
	
	
	public static int getImageDirTrack(IMAGE_TYPE type)
	{
		if(type.equals(IMAGE_TYPE.D64))
			return 18;
		if(type.equals(IMAGE_TYPE.D71))
			return 18;
		if(type.equals(IMAGE_TYPE.D81))
			return 40;
		if(type.equals(IMAGE_TYPE.D80)||type.equals(IMAGE_TYPE.D82))
			return 39;
		return -1;
	}
	
	public static int getImageDirSector(IMAGE_TYPE type)
	{
		if(type.equals(IMAGE_TYPE.D64))
			return 1;
		if(type.equals(IMAGE_TYPE.D71))
			return 1;
		if(type.equals(IMAGE_TYPE.D81))
			return 3;
		if(type.equals(IMAGE_TYPE.D80)||type.equals(IMAGE_TYPE.D82))
			return 1;
		return -1;
	}
	
	private static int getImageNumTracks(IMAGE_TYPE type)
	{
		if(type.equals(IMAGE_TYPE.D64))
			return 35;
		else
		if(type.equals(IMAGE_TYPE.D71))
			return 70;
		else
		if(type.equals(IMAGE_TYPE.D81))
			return 79;
		else
		if(type.equals(IMAGE_TYPE.D80))
			return 77;
		else
		if(type.equals(IMAGE_TYPE.D82))
			return 2*77;
		return -1;
	}
	
	private static char convertToPetscii(byte b)
	{
		if(b<65) return (char)b;
		if(b<91) return Character.toLowerCase((char)b);
		if(b<192) return (char)b;
		if(b<218) return Character.toUpperCase((char)b);
		return (char)b;
	}
	
	private static byte[][][] parseMap(IMAGE_TYPE type, byte[] buf)
	{
		int numTS=getImageNumTracks(type);
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
		byte[] buf=new byte[getImageTotalBytes(type)];
		try
		{
			FileInputStream is=new FileInputStream(F);
			int i=0;
			while(i<buf.length)
			{
				int x=is.read();
				buf[i++]=(byte)x;
			}
		}
		catch(java.io.IOException e)
		{
			e.printStackTrace();
		}
		return parseMap(type,buf);
	}
	
	public static String toHex(byte b){ return HEX[unsigned(b)];}
    public static String toHex(byte[] buf){
        StringBuffer ret=new StringBuffer("");
        for(int b=0;b<buf.length;b++)
            ret.append(toHex(buf[b]));
        return ret.toString();
    }
	public static short fromHex(String hex){ return ((Short)ANTI_HEX.get(hex)).shortValue();}
	public static byte[] getFileContent(byte[][][] tsmap, int t, int mt, int s, FILE_FORMAT fmt)
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
					if(fmt==FILE_FORMAT.PETSCII)
						out.write((byte)convertToPetscii(sector[i]));
					else
	                    out.write(sector[i]);
				t=unsigned(sector[0]);
				s=unsigned(sector[1]);
			}
			return out.toByteArray();
		}
		catch(Throwable th)
		{
			th.printStackTrace();
			return null;
		}
	}
    
    public static short unsigned(byte b){ return (short)(0xFF & b);}
    
	
	public static Vector<Object> getFiledata(IMAGE_TYPE type, byte[][][] tsmap, HashSet<SEARCH_FLAG> flags, FILE_FORMAT fmt)
	{
		int t=getImageDirTrack(type);
		int maxT=D64Search.getImageNumTracks(type);
		int s=getImageDirSector(type);
		Vector<Object> finalData=new Vector<Object>();
		Vector<String> fileNames=new Vector<String>();
        boolean inside=flags.contains(SEARCH_FLAG.INSIDE);
        boolean md5=flags.contains(SEARCH_FLAG.SHOWMD5);
		Vector<String> types=flags.contains(SEARCH_FLAG.VERBOSE)?new Vector<String>():null;
		Vector<String> sizes=flags.contains(SEARCH_FLAG.VERBOSE)?new Vector<String>():null;
		Vector<byte[]> data=(inside||md5)?new Vector<byte[]>():null;
		byte[] sector=tsmap[t][s];
		HashSet<byte[]> doneBefore=new HashSet<byte[]>();
		while((t!=0)&&(!doneBefore.contains(tsmap[t][s]))&&(t<=maxT))
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
				if((fmt==FILE_FORMAT.PETSCII)||inside)
					for(int x=i+3;x<=fn;x++)
						file.append(convertToPetscii(sector[x]));
				else
				if(fmt==FILE_FORMAT.ASCII)
					for(int x=i+3;x<=fn;x++)
						file.append((char)sector[x]);
				else
				for(int x=i+3;x<=fn;x++)
					file.append(toHex(sector[x]));
					
				if(file.length()>0)
				{
					fileNames.add(file.toString());
					if(sizes!=null)
					{
                        short lb=unsigned(sector[i+28]);
                        short hb=unsigned(sector[i+29]);
						int size=(256*(lb+(256*hb)));
						if(size<0) System.out.println(lb+","+hb+","+size);
                        sizes.add(new Integer(size).toString());
					}
					if(types!=null)
						switch(sector[i])
						{
						case (byte)129: types.add(", seq"); break;
						case (byte)130: types.add(", prg"); break;
						case (byte)131: types.add(", usr"); break;
						case (byte)132: types.add(", rel"); break;
						default: types.add(",?");break;
						}
					if(data!=null)
					{
						int fileT=unsigned(sector[i+1]);
						int fileS=unsigned(sector[i+2]);
						byte[] fileData =getFileContent(tsmap,fileT,maxT,fileS,fmt);
						if(fileData==null)
						{
							System.err.println("Error reading: "+fileT+","+fileS);
							return null;
						}
						else
							data.addElement(fileData);
					}
				}
			}
			t=unsigned(sector[0]);
			s=unsigned(sector[1]);
		}
		finalData.addElement(toStringList(fileNames));
		if(types!=null)
			finalData.addElement(toStringList(types));
		else
			finalData.addElement(new String[fileNames.size()]);
		if(sizes!=null)
			finalData.addElement(toStringList(sizes));
		else
			finalData.addElement(new String[fileNames.size()]);
		if(data!=null)
			finalData.addElement(toByteList(data));
		else
			finalData.addElement(new byte[fileNames.size()][0]);
		return finalData;
	}

	private static String[] toStringList(Vector<String> V)
	{
		String[] files=new String[V.size()];
		for(int f=0;f<V.size();f++)
			files[f]=(String)V.elementAt(f);
		return files;
	}
	private static byte[][] toByteList(Vector<byte[]> V)
	{
		byte[][] files=new byte[V.size()][];
		for(int f=0;f<V.size();f++)
			files[f]=(byte[])V.elementAt(f);
		return files;
	}
    
	private static boolean check(String name, char[] expr, boolean caseSensitive)
	{
		int n=0;
		int ee=0;
		if((expr.length>1)&&(expr[0]=='%'))
		{
			for(;n<name.length();n++)
				if(name.charAt(n)==expr[1])
				{
					break;
				}
			ee=1;
		}
		for(int e=ee;e<expr.length;e++)
			switch(expr[e])
			{
			case '?':
				if(n+e>=name.length())
					return false;
				break;
			case '%':
				return true;
			default:
				if(n+e-ee>=name.length())
					return false;
				if(expr[e]!=name.charAt(n+e-ee))
					return false;
				break;
			}
		return name.length()-n==expr.length-ee;
	}

	private static boolean checkInside(byte[] buf, char[] expr, HashSet<SEARCH_FLAG> flags, FILE_FORMAT fmt, boolean caseSensitive)
	{
		if(!caseSensitive)
        {
            byte[] chk=new byte[buf.length];
            for(int b=0;b<buf.length;b++)
                chk[b]=(byte)Character.toUpperCase((char)buf[b]);
            buf=chk;
        }
        boolean byteFormat=fmt==FILE_FORMAT.HEX;
        int bb=0;
        int e=0;
        for(int b=0;b<buf.length;b++)
        {
            for(e=0,bb=0;e<=expr.length;e++,bb++)
                if(e==expr.length)
                    return true;
                else
                if((expr[e]=='?')||(expr[e]=='%'))
                    continue;
                else
                if((b+bb)>=buf.length)
                    return false;
                else
                if(byteFormat)
                {
                    if(e<expr.length-1)
                    {
                        if(buf[b+bb]!=D64Search.fromHex(""+expr[e]+expr[e+1]))
                            break;
                        e++;
                    }
                }
                else
                if(expr[e]!=(char)buf[b+bb])
                    break;
        }
		return false;
	}
	
	private static void search(File F, char[] expr, HashSet<SEARCH_FLAG> flags, FILE_FORMAT  fmt)
	{
		if(F.isDirectory())
		{
			File[] files=F.listFiles();
			for(int f=0;f<files.length;f++)
				search(files[f],expr,flags,fmt);
		}
		else
		{
			boolean caseSensitive=flags.contains(SEARCH_FLAG.CASESENSITIVE);
			boolean inside=flags.contains(SEARCH_FLAG.INSIDE);
            MessageDigest MD=null;
            if(flags.contains(SEARCH_FLAG.SHOWMD5))
            {
                try{MD=MessageDigest.getInstance("MD5");}catch(Exception e){e.printStackTrace();}
            }
			for(IMAGE_TYPE img : IMAGE_TYPE.values())
				if(F.getName().toUpperCase().endsWith(img.toString()))
				{
					IMAGE_TYPE type=img;
					byte[][][] disk=getDisk(type,F);
					Vector<Object> fileData=getFiledata(type,disk,flags,fmt);
					if(fileData==null)
					{
						System.err.println("Error reading :"+F.getName());
						continue;
					}
					String[] names=(String[])fileData.firstElement();
					if(names.length>0)
					{
						boolean announced=false;
						for(int n=0;n<names.length;n++)
							if((inside&&(checkInside(((byte[][])fileData.lastElement())[n],expr,flags,fmt,caseSensitive)))
							||check(caseSensitive?names[n]:names[n].toUpperCase(),expr,caseSensitive))
							{
								if(!announced)
								{
									System.out.println(F.getName());
									announced=true;
								}
								String name=names[n];
								if(!flags.contains(SEARCH_FLAG.INSIDE))
								{
									if(fmt==FILE_FORMAT.ASCII)
									{
										StringBuffer newName=new StringBuffer("");
										for(int x=0;x<name.length();x++)
											newName.append(D64Search.convertToPetscii((byte)name.charAt(x)));
										name=newName.toString();
									}
									else
                                    if(fmt==FILE_FORMAT.HEX)
									{
										StringBuffer newName=new StringBuffer("");
										for(int x=0;x<name.length();x+=2)
											newName.append((char)fromHex(name.substring(0,2)));
										name=newName.toString();
									}
								}
                                System.out.print("  "+names[n]);
								if((flags.contains(SEARCH_FLAG.VERBOSE)))
								{
									String[] types=(String[])fileData.elementAt(1);
									String[] sizes=(String[])fileData.elementAt(2);
									System.out.print(types[n]+", "+sizes[n]+" bytes");
								}
                                if((flags.contains(SEARCH_FLAG.SHOWMD5)))
                                {
                                    MD.update(((byte[][])fileData.lastElement())[n]);
                                    System.out.print(", MD5: "+toHex(MD.digest()));
                                }
                                System.out.println("");
							}
					}
					break;
				}
		}
	}
	
	public static void main(String[] args)
	{
		if(args.length<2)
		{
			System.out.println("USAGE: D64Search <options> <path> <expression>");
			System.out.println("OPTIONS:");
			System.out.println("  -R recursive search");
			System.out.println("  -V verbose");
            System.out.println("  -M show MD5 sum for each matching file");
			System.out.println("  -C case sensitive");
			System.out.println("  -X expr format (-Xp=petscii, Xa=ascii, Xh=hex)");
			System.out.println("  -I search inside files (substring search)");
			System.out.println("");
			System.out.println("");
			System.out.println("* Expressions include % and ? characters.");
			System.out.println("* Hex expressions include hex digits, *, and ?.");
			return;
		}
		HashSet<SEARCH_FLAG> flags=new HashSet<SEARCH_FLAG>();
		FILE_FORMAT fmt=FILE_FORMAT.PETSCII;
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
					case 'R': flags.add(SEARCH_FLAG.RECURSE); break;
					case 'c':
					case 'C': flags.add(SEARCH_FLAG.CASESENSITIVE); break;
					case 'v':
					case 'V': flags.add(SEARCH_FLAG.VERBOSE); break;
					case 'i':
					case 'I': flags.add(SEARCH_FLAG.INSIDE); break;
                    case 'm':
                    case 'M': flags.add(SEARCH_FLAG.SHOWMD5); break;
					case 'x':
					case 'X':
					{
						int x=(c<args[i].length()-1)?"PAH".indexOf(Character.toUpperCase(args[i].charAt(c+1))):-1;
						if(x<0)
						{
							System.out.println("Error: -x  must be followed by a,p,or h");
							return;
						}
						fmt=FILE_FORMAT.values()[x];
						break;
					}
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
		if((expr.length()>1)&&(expr.startsWith("%"))
		&&((expr.charAt(1)=='%')||(expr.charAt(1)=='?')))
		{
			System.out.println("illegal %? expression error.");
			return;
		}
		if(path==null)
		{
			System.out.println("Path not found!");
			return;
		}
		char[] exprCom=expr.toCharArray();
		if((!flags.contains(SEARCH_FLAG.CASESENSITIVE))||(fmt==FILE_FORMAT.HEX))
			for(int e=0;e<exprCom.length;e++)
				exprCom[e]=Character.toUpperCase(exprCom[e]);
		if(fmt==FILE_FORMAT.HEX)
			for(int e=0;e<exprCom.length;e++)
				if((exprCom[e]!='?')&&(exprCom[e]!='%')&&(HEX_DIG.indexOf(exprCom[e])<0))
				{
					System.out.println("Illegal hex '"+exprCom[e]+"' in expression.");
					return;
				}
		File F=new File(path);
		if(F.isDirectory())
		{
			File[] files=F.listFiles();
			for(int f=0;f<files.length;f++)
				search(files[f],exprCom,flags,fmt);
		}
		else
			search(F,exprCom,flags,fmt);
	}
}
