package com.planet_ink.emutil;
import java.io.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
/* 
Copyright 2006-2015 Bo Zimmerman

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
public class D64Search 
{
	// todo: add file masks to options
	public enum IMAGE_TYPE {
		D64 { public String toString() { return ".D64";}},
		D71 { public String toString() { return ".D71";}},
		D81 { public String toString() { return ".D81";}},
		D80 { public String toString() { return ".D80";}},
		D82 { public String toString() { return ".D82";}},
	};

	public static class DatabaseInfo {
		String user=null;
		String pass=null;
		String className=null;
		String service=null;
		String table=null;
		Connection conn = null;
		PreparedStatement stmt = null;
		public boolean filled(){ return (user!=null)&&(pass!=null)&&(className!=null)&&(service!=null)&&(table!=null);}
	}

	public static class FileInfo {
		String fileName = "";
		String fileType = "";
		int size = 0;
		byte[] data = null;
	}
	
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
		if(b<219) return Character.toUpperCase((char)(b-128));
		return (char)(b-128);
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
		return parseMap(type,buf);
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
			th.printStackTrace(System.err);
			return null;
		}
	}

	public static short unsigned(byte b)
	{
		return (short)(0xFF & b);
	}
	
	public static Vector<FileInfo> getFiledata(IMAGE_TYPE type, byte[][][] tsmap, HashSet<SEARCH_FLAG> flags, FILE_FORMAT fmt)
	{
		int t=getImageDirTrack(type);
		int maxT=D64Search.getImageNumTracks(type);
		int s=getImageDirSector(type);
		Vector<FileInfo> finalData=new Vector<FileInfo>();
		boolean inside=flags.contains(SEARCH_FLAG.INSIDE);
		boolean md5=flags.contains(SEARCH_FLAG.SHOWMD5);
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
					FileInfo f = new FileInfo();
					finalData.add(f);
					f.fileName=file.toString();
					if(flags.contains(SEARCH_FLAG.VERBOSE))
					{
						short lb=unsigned(sector[i+28]);
						short hb=unsigned(sector[i+29]);
						int size=(256*(lb+(256*hb)));
						if (size < 0)
							System.err.println("Error: Invalid Size: " + lb + "," + hb + "," + size);
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
					if(inside||md5)
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
							f.data = fileData;
					}
				}
			}
			t=unsigned(sector[0]);
			s=unsigned(sector[1]);
		}
		return finalData;
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
	
	private static void search(File F, char[] expr, HashSet<SEARCH_FLAG> flags, FILE_FORMAT  fmt, DatabaseInfo dbInfo)
	{
		if(F.isDirectory())
		{
			File[] files=F.listFiles();
			for(int f=0;f<files.length;f++)
				search(files[f],expr,flags,fmt, dbInfo);
		}
		else
		{
			boolean caseSensitive=flags.contains(SEARCH_FLAG.CASESENSITIVE);
			boolean inside=flags.contains(SEARCH_FLAG.INSIDE);
			MessageDigest MD=null;
			if(flags.contains(SEARCH_FLAG.SHOWMD5))
			{
				try{MD=MessageDigest.getInstance("MD5");}catch(Exception e){e.printStackTrace(System.err);}
			}
			for(IMAGE_TYPE img : IMAGE_TYPE.values())
				if(F.getName().toUpperCase().endsWith(img.toString()))
				{
					IMAGE_TYPE type=img;
					byte[][][] disk=getDisk(type,F);
					if(dbInfo!=null)
					{
						try
						{
							dbInfo.stmt.clearParameters();
							MD.reset();
							//ByteArrayOutputStream bout = new ByteArrayOutputStream();
							long diskLen=0;
							for(int b1=0;b1<disk.length;b1++)
								for(int b2=0;b2<disk[b1].length;b2++)
								{
									MD.update(disk[b1][b2]);
									diskLen += disk[b1][b2].length;
									//bout.write(disk[b1][b2]);
								}
							byte[] md5 = MD.digest();
							dbInfo.stmt.setString(1, F.getName());
							dbInfo.stmt.setString(2, "*");
							dbInfo.stmt.setInt(3, 0);
							dbInfo.stmt.setLong(4, diskLen);
							dbInfo.stmt.setString(5, toHex(md5));
							dbInfo.stmt.setString(6, "dsk");
							dbInfo.stmt.addBatch();
						}
						catch(Exception e)
						{
							System.err.println("Stupid preparedStatement error: "+e.getMessage());
						}
					}
					Vector<FileInfo> fileData=getFiledata(type,disk,flags,fmt);
					if(fileData==null)
					{
						System.err.println("Error reading :"+F.getName());
						continue;
					}
					boolean announced=false;
					byte[] md5 = null;
					for(int n=0;n<fileData.size();n++)
					{
						md5=null;
						FileInfo f = fileData.elementAt(n);
						if((inside&&(checkInside(f.data,expr,flags,fmt,caseSensitive)))
						||check(caseSensitive?f.fileName:f.fileName.toUpperCase(),expr,caseSensitive))
						{
							if(!announced)
							{
								System.out.println(F.getName());
								announced=true;
							}
							String name=f.fileName;
							StringBuffer asciiName=new StringBuffer("");
							for(int x=0;x<name.length();x++)
								asciiName.append(D64Search.convertToPetscii((byte)name.charAt(x)));
							if(dbInfo!=null)
							{
								MD.reset();
								MD.update(f.data);
								md5 = MD.digest();
								try
								{
									dbInfo.stmt.clearParameters();
									dbInfo.stmt.setString(1, F.getName());
									dbInfo.stmt.setString(2, asciiName.toString());
									dbInfo.stmt.setInt(3, (n+1));
									dbInfo.stmt.setInt(4, f.size);
									dbInfo.stmt.setString(5, toHex(md5));
									dbInfo.stmt.setString(6, f.fileType);
									dbInfo.stmt.addBatch();
								}
								catch(SQLException e)
								{
									System.err.println("Stupid preparedStatement error: "+e.getMessage());
								}
							}
							if(!flags.contains(SEARCH_FLAG.INSIDE))
							{
								if(fmt==FILE_FORMAT.ASCII)
									name=asciiName.toString();
								else
								if(fmt==FILE_FORMAT.HEX)
								{
									StringBuffer newName=new StringBuffer("");
									for(int x=0;x<name.length();x+=2)
										newName.append((char)fromHex(name.substring(0,2)));
									name=newName.toString();
								}
							}
							System.out.print("  "+asciiName.toString());
							if((flags.contains(SEARCH_FLAG.VERBOSE)))
								System.out.print(","+f.fileType+", "+f.size+" bytes");
							if((flags.contains(SEARCH_FLAG.SHOWMD5)))
							{
								if(md5==null)
								{
									MD.reset();
									MD.update(f.data);
									md5=MD.digest();
								}
								System.out.print(", MD5: "+toHex(md5));
							}
							System.out.println("");
						}
					}
					break;
				}
			if(dbInfo!=null)
			{
				try
				{
					dbInfo.stmt.executeBatch();
				}
				catch(SQLException e)
				{
					System.err.println("SQL preparedStatement execute batch error: "+e.getMessage());
				}
			}
		}
	}
	
	public static void main(String[] args)
	{
		if(args.length<2)
		{
			System.out.println("D64Search v1.5 (c)2006-2015 Bo Zimmerman");
			System.out.println("");
			System.out.println("USAGE: ");
			System.out.println("  D64Search <options> <path> <expression>");
			System.out.println("OPTIONS:");
			System.out.println("  -R recursive search");
			System.out.println("  -V verbose");
			System.out.println("  -M show MD5 sum for each matching file");
			System.out.println("  -C case sensitive");
			System.out.println("  -X expr fmt (-Xp=petscii, Xa=ascii, Xh=hex)");
			System.out.println("  -I search inside files (substring search)");
			System.out.println("  -D db export of disk info data (-Du<user>,");
			System.out.println("     -Dp<password>, -Dc<java class>,");
			System.out.println("     -Ds<service> -Dt<tablename>)");
			System.out.println("     (Columns: string imagepath,");
			System.out.println("               string filename, int filenum,");
			System.out.println("               long size, string md5,");
			System.out.println("               string filetype)");
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
		DatabaseInfo dbInfo = null;
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
							System.err.println("Error: -x  must be followed by a,p,or h");
							return;
						}
						fmt=FILE_FORMAT.values()[x];
						break;
					}
					case 'd':
					case 'D':
					{
						int x=(c<args[i].length()-1)?"UPCST".indexOf(Character.toUpperCase(args[i].charAt(c+1))):-1;
						if(x<0)
						{
							System.err.println("Error: -d  must be followed by u, p, c, s, or t");
							return;
						}
						if(dbInfo==null) dbInfo = new DatabaseInfo();
						switch(Character.toLowerCase(args[i].charAt(c+1)))
						{
						case 'u': dbInfo.user=args[i].substring(c+2); break;
						case 'p': dbInfo.pass=args[i].substring(c+2); break;
						case 'c': dbInfo.className=args[i].substring(c+2); break;
						case 's': dbInfo.service=args[i].substring(c+2); break;
						case 't': dbInfo.table=args[i].substring(c+2); break;
						}
						c=args[i].length()-1;
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
			System.err.println("illegal %? expression error.");
			return;
		}
		if(path==null)
		{
			System.err.println("Path not found!");
			return;
		}
		if(dbInfo!=null)
		{
			System.out.println("DBInfo:\n");
			System.out.println("user	 :" + dbInfo.user);
			System.out.println("pass	 :" + dbInfo.pass);
			System.out.println("className:" + dbInfo.className);
			System.out.println("service  :" + dbInfo.service);
			System.out.println("table	:" + dbInfo.table);
			if(!dbInfo.filled())
			{
				System.err.println("DBInfo incomplete!");
				return;
			}
			flags.add(SEARCH_FLAG.VERBOSE); 
			flags.add(SEARCH_FLAG.SHOWMD5); 
			try
			{
				Class.forName(dbInfo.className);
				dbInfo.conn = DriverManager.getConnection(dbInfo.service,dbInfo.user,dbInfo.pass );
				dbInfo.stmt=dbInfo.conn.prepareStatement("insert into "+dbInfo.table+" (imagepath, filename, filenum,size, md5, filetype) values (?,?,?,?,?,?)");
			}
			catch(Exception e)
			{
				System.err.println("Unable to connect to database: "+e.getMessage());
				return;
			}
		}
		char[] exprCom=expr.toCharArray();
		if((!flags.contains(SEARCH_FLAG.CASESENSITIVE))||(fmt==FILE_FORMAT.HEX))
			for(int e=0;e<exprCom.length;e++)
				exprCom[e]=Character.toUpperCase(exprCom[e]);
		if(fmt==FILE_FORMAT.HEX)
			for(int e=0;e<exprCom.length;e++)
				if((exprCom[e]!='?')&&(exprCom[e]!='%')&&(HEX_DIG.indexOf(exprCom[e])<0))
				{
					System.err.println("Illegal hex '"+exprCom[e]+"' in expression.");
					return;
				}
		File F=new File(path);
		if(F.isDirectory())
		{
			File[] files=F.listFiles();
			for(int f=0;f<files.length;f++)
				search(files[f],exprCom,flags,fmt,dbInfo);
		}
		else
			search(F,exprCom,flags,fmt, dbInfo);
	}
}
