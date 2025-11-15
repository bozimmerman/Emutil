package com.planet_ink.emutil;
import java.io.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

import com.planet_ink.emutil.CBMDiskImage.FileInfo;
import com.planet_ink.emutil.CBMDiskImage.ImageType;
/*
Copyright 2006-2024 Bo Zimmerman

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
public class D64Search extends D64Mod
{
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

	public enum SEARCH_FLAG {
		CASESENSITIVE,
		VERBOSE,
		RECURSE,
		INSIDE,
		SHOWMD5,
		UNZIP,
		FULLPATH,
		NOPARSEERRORS,
		LOOSEFILES;
	};

	public enum FILE_FORMAT {
		PETSCII,
		ASCII,
		HEX;
	};

	public enum PLATFORM {
		PET((byte)0x01, (byte)0x04),
		VIC((byte)0x01, (byte)0x12),
		C64((byte)0x01, (byte)0x08),
		CBM2((byte)0x03, (byte)0x00),
		C128((byte)0x01, (byte)0x1c),
		C65((byte)0x01, (byte)0x20),
		;
		public byte[] bytes;
		private PLATFORM(final byte b1, final byte b2)
		{
			bytes = new byte[] {b1, b2};
		}
	}

	private static boolean check(final String name, final char[] expr, final boolean caseSensitive)
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

	private static boolean checkFilterOut(final byte[] buf, final Set<PLATFORM> pfilter)
	{
		if((pfilter==null)||(pfilter.size()==0))
			return true;
		if((buf==null)||(buf.length<3))
			return false;
		for(final PLATFORM P : pfilter)
			if((P.bytes[0]==buf[0])&&(P.bytes[1]==buf[1]))
				return true;
		return false;
	}

	private static boolean checkInside(byte[] buf, final char[] expr, final Set<SEARCH_FLAG> flags, final FILE_FORMAT fmt, final boolean caseSensitive)
	{
		if(!caseSensitive)
		{
			final byte[] chk=new byte[buf.length];
			for(int b=0;b<buf.length;b++)
				chk[b]=(byte)Character.toUpperCase((char)buf[b]);
			buf=chk;
		}
		final boolean byteFormat=fmt==FILE_FORMAT.HEX;
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

	private static class SearchFile
	{
		public String filePath = "";
		public String fileName = "";
		public byte[] diskData = null;
		public List<FileInfo> diskFiles = new ArrayList<FileInfo>();
	}

	private static List<SearchFile> parseSearchFiles(final File F, final Set<SEARCH_FLAG> flags)
	{
		List<SearchFile> files = null;
		final boolean unzip = flags.contains(SEARCH_FLAG.UNZIP);
		final boolean doLoose = flags.contains(SEARCH_FLAG.LOOSEFILES);
		final IOFile zF = new IOFile(F);
		if((zF.isDirectory()
			||(F.getName().toUpperCase().endsWith(".GZ"))
			||(F.getName().toUpperCase().endsWith(".ZIP")))
		&&(!unzip))
			return null;
		Iterable<IOFile> vF;
		if(zF.isDirectory())
			vF = Arrays.asList(zF.listFiles());
		else
			vF = Arrays.asList(new IOFile[] { zF });
		try
		{
			for(final IOFile zE : vF)
			{
				if(zE.isDirectory())
					continue;
				if(zE.length()>83361792)
					break;
				final ImageType img = CBMDiskImage.getImageTypeAndGZipped(zE.getName());
				if (img != null)
				{
					final String prefix = (zE == zF) ? "" : F.getName()+"@";
					final SearchFile sF = new SearchFile();
					final BitSet parseFlags = new BitSet();
					parseFlags.set(PF_RECURSE, flags.contains(SEARCH_FLAG.RECURSE));
					parseFlags.set(PF_NOERRORS, flags.contains(SEARCH_FLAG.NOPARSEERRORS));
					parseFlags.set(D64Base.PF_READINSIDE, true);
					final CBMDiskImage disk = new CBMDiskImage(zE);
					disk.parseFlags = parseFlags;
					disk.getLength(); // necessary to cache data before zipFile is closed
					sF.diskFiles.addAll(disk.getFiles(parseFlags));
					sF.diskData = disk.getFlatBytes();
					sF.fileName = prefix+zE.getName();
					if(zE == zF)
						sF.filePath = F.getAbsolutePath();
					else
						sF.filePath = F.getAbsolutePath()+"@"+zE.getName();
					if(files == null)
						files = new ArrayList<SearchFile>();
					files.add(sF);
				}
				else
				if(doLoose)
				{
					final D64Base.LooseCBMFileType looseType = D64Base.getLooseImageTypeAndGZipped(zE);
					if(looseType == null)
						continue;
					final String prefix = (zE == zF) ? "" : F.getName()+"@";
					final SearchFile sF = new SearchFile();
					final FileInfo lF = D64Base.getLooseFile(zE.F);
					if(lF == null)
						continue;
					sF.diskData = lF.data;;
					sF.diskFiles.add(lF);
					sF.fileName = prefix+zE.getName();
					if(zE == zF)
						sF.filePath = F.getAbsolutePath();
					else
						sF.filePath = F.getAbsolutePath()+"@"+zE.getName();
					if(files == null)
						files = new ArrayList<SearchFile>();
					files.add(sF);
				}
			}
			zF.close();
		}
		catch(final Exception e)
		{
			e.printStackTrace();
			System.err.print("\r\nFailed: "+F.getAbsolutePath()+": "+e.getMessage());
		}
		return files;
	}

	private static boolean searchDiskFiles(final List<SearchFile> files, final char[] expr,
			final Set<SEARCH_FLAG> flags, final FILE_FORMAT  fmt, final DatabaseInfo dbInfo,
			final Set<PLATFORM> pfilter, final MessageDigest MD)
	{
		final boolean caseSensitive=flags.contains(SEARCH_FLAG.CASESENSITIVE);
		final boolean inside=flags.contains(SEARCH_FLAG.INSIDE);
		final boolean fullPath=flags.contains(SEARCH_FLAG.FULLPATH);
		boolean batchAdded=false;
		for(final SearchFile F : files)
		{
			final String fileName = fullPath?F.filePath:F.fileName;
			if(dbInfo!=null)
			{
				try
				{
					dbInfo.stmt.clearParameters();
					MD.reset();
					//ByteArrayOutputStream bout = new ByteArrayOutputStream();
					final byte[] flatBytes = F.diskData;
					MD.update(flatBytes);
					final byte[] md5 = MD.digest();
					dbInfo.stmt.setString(1, fileName);
					dbInfo.stmt.setString(2, "*");
					dbInfo.stmt.setInt(3, 0);
					dbInfo.stmt.setLong(4, flatBytes.length);
					dbInfo.stmt.setString(5, toHex(md5));
					dbInfo.stmt.setString(6, "dsk");
					dbInfo.stmt.addBatch();
					batchAdded=true;
				}
				catch(final Exception e)
				{
					System.err.println("Stupid preparedStatement error: "+e.getMessage());
				}
			}
			final List<FileInfo> fileData=F.diskFiles;
			if((fmt==FILE_FORMAT.PETSCII)||inside)
			{
				for(final FileInfo info : fileData)
				{
					final StringBuilder str=new StringBuilder("");
					for(final byte b : info.fileName.getBytes())
						str.append(D64Search.convertToPetscii(b));
					info.fileName = str.toString();
				}
			}
			else
			if(fmt==FILE_FORMAT.ASCII)
			{
				for(final FileInfo info : fileData)
				{
					final StringBuilder str=new StringBuilder("");
					for(final byte b : info.fileName.getBytes())
						str.append((char)b);
					info.fileName = str.toString();
				}
			}
			else
			if(fmt==FILE_FORMAT.ASCII)
			{
				for(final FileInfo info : fileData)
				{
					final StringBuilder str=new StringBuilder("");
					for(final byte b : info.fileName.getBytes())
						str.append(toHex(b));
					info.fileName = str.toString();
				}
			}
			if(fmt==FILE_FORMAT.PETSCII)
			{
				for(final FileInfo info : fileData)
				{
					if(info.data!=null)
					{
						for(int i=0;i<info.data.length;i++)
							info.data[i]=(byte)(D64Search.convertToPetscii(info.data[i]) & 0xff);
					}
				}
			}

			if(fileData==null)
			{
				System.err.println("Error reading :"+fileName);
				continue;
			}
			boolean announced=false;
			byte[] md5 = null;
			for(int n=0;n<fileData.size();n++)
			{
				md5=null;
				final FileInfo f = fileData.get(n);
				if(f.data==null)
					continue;
				if(((inside&&(checkInside(f.data,expr,flags,fmt,caseSensitive)))
					||check(caseSensitive?f.fileName:f.fileName.toUpperCase(),expr,caseSensitive))
				&&(D64Search.checkFilterOut(f.data, pfilter)))
				{
					if(!announced)
					{
						System.out.println(fileName);
						announced=true;
					}
					String name=f.fileName;
					final StringBuffer asciiName=new StringBuffer("");
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
							dbInfo.stmt.setString(1, fileName);
							dbInfo.stmt.setString(2, asciiName.toString());
							dbInfo.stmt.setInt(3, (n+1));
							dbInfo.stmt.setInt(4, f.size);
							dbInfo.stmt.setString(5, toHex(md5));
							dbInfo.stmt.setString(6, f.fileType.toString().toLowerCase());
							dbInfo.stmt.addBatch();
							batchAdded=true;
						}
						catch(final SQLException e)
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
							final StringBuffer newName=new StringBuffer("");
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
		}
		return batchAdded;
	}

	private static void searchFilesystem(final File F, final char[] expr, final Set<SEARCH_FLAG> flags,
			final FILE_FORMAT  fmt, final DatabaseInfo dbInfo, final Set<PLATFORM> pfil, final Set<File> pathsDone)
	{
		if(F.isDirectory())
		{
			if(flags.contains(SEARCH_FLAG.RECURSE)
			&&(!pathsDone.contains(F)))
			{
				pathsDone.add(F);
				final File[] files=F.listFiles();
				for(int f=0;f<files.length;f++)
					searchFilesystem(files[f],expr,flags,fmt, dbInfo,pfil,pathsDone);
			}
		}
		else
		{
			MessageDigest MD=null;
			if(flags.contains(SEARCH_FLAG.SHOWMD5))
			{
				try{MD=MessageDigest.getInstance("MD5");}catch(final Exception e){e.printStackTrace(System.err);}
			}
			final List<SearchFile> files = parseSearchFiles(F,flags);
			if(files != null)
			{
				if(searchDiskFiles(files,expr,flags,fmt,dbInfo,pfil,MD))
				{
					try
					{
						dbInfo.stmt.executeBatch();
					}
					catch(final SQLException e)
					{
						System.err.println("SQL preparedStatement execute batch error: "+e.getMessage());
					}
				}
			}
		}
	}

	public static void main(final String[] args)
	{
		if(args.length<2)
		{
			System.out.println("D64Search v"+EMUTIL_VERSION+" (c)2006-"+EMUTIL_AUTHOR);
			System.out.println("");
			System.out.println("USAGE: ");
			System.out.println("  D64Search <options> <path> <expression>");
			System.out.println("OPTIONS:");
			System.out.println("  -R recursive path search");
			System.out.println("  -V verbose");
			System.out.println("  -M show MD5 sum for each matching file");
			System.out.println("  -C case sensitive");
			System.out.println("  -Z unzip archives");
			System.out.println("  -F full absolute paths");
			System.out.println("  -P platform filter (-Pp, -Pv, -P6, etc)");
			System.out.println("     (p)et, (v)ic, c(6)4, c12(8), cbm(2), c6(5)");
			System.out.println("  -L include 'loose' files");
			System.out.println("  -Q suppress parse errors");
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
		final Set<SEARCH_FLAG> flags=new HashSet<SEARCH_FLAG>();
		FILE_FORMAT fmt=FILE_FORMAT.PETSCII;
		String path=null;
		String expr="";
		DatabaseInfo dbInfo = null;
		final Set<PLATFORM> pfilter = new HashSet<PLATFORM>();
		for(int i=0;i<args.length;i++)
		{
			if((args[i].startsWith("-")&&(path==null)))
			{
				for(int c=1;c<args[i].length();c++)
				{
					switch(args[i].charAt(c))
					{
					case 'r':
					case 'R':
						flags.add(SEARCH_FLAG.RECURSE);
						break;
					case 'q':
					case 'Q':
						flags.add(SEARCH_FLAG.NOPARSEERRORS);
						break;
					case 'c':
					case 'C':
						flags.add(SEARCH_FLAG.CASESENSITIVE);
						break;
					case 'l':
					case 'L':
						flags.add(SEARCH_FLAG.LOOSEFILES);
						break;
					case 'v':
					case 'V':
						flags.add(SEARCH_FLAG.VERBOSE);
						break;
					case 'i':
					case 'I':
						flags.add(SEARCH_FLAG.INSIDE);
						break;
					case 'z':
					case 'Z':
						flags.add(SEARCH_FLAG.UNZIP);
						break;
					case 'm':
					case 'M':
						flags.add(SEARCH_FLAG.SHOWMD5);
						break;
					case 'f':
					case 'F':
						flags.add(SEARCH_FLAG.FULLPATH);
						break;
					case 'p':
					case 'P':
					{
						final int x=(c<args[i].length()-1)?"PV6825".indexOf(Character.toUpperCase(args[i].charAt(c+1))):-1;
						if(x<0)
						{
							System.err.println("Error: -P  must be followed by p, v, 6, 8, 2, or 5");
							return;
						}
						switch(Character.toUpperCase(args[i].charAt(c+1)))
						{
						case 'P':
							pfilter.add(PLATFORM.PET);
							break;
						case 'V':
							pfilter.add(PLATFORM.VIC);
							break;
						case '6':
							pfilter.add(PLATFORM.C64);
							break;
						case '8':
							pfilter.add(PLATFORM.C128);
							break;
						case '2':
							pfilter.add(PLATFORM.CBM2);
							break;
						case '5':
							pfilter.add(PLATFORM.C65);
							break;
						}
						c++;
						break;
					}
					case 'x':
					case 'X':
					{
						final int x=(c<args[i].length()-1)?"PAH".indexOf(Character.toUpperCase(args[i].charAt(c+1))):-1;
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
						final int x=(c<args[i].length()-1)?"UPCST".indexOf(Character.toUpperCase(args[i].charAt(c+1))):-1;
						if(x<0)
						{
							System.err.println("Error: -d  must be followed by u, p, c, s, or t");
							return;
						}
						if(dbInfo==null) dbInfo = new DatabaseInfo();
						switch(Character.toLowerCase(args[i].charAt(c+1)))
						{
						case 'u':
							dbInfo.user = args[i].substring(c + 2);
							break;
						case 'p':
							dbInfo.pass = args[i].substring(c + 2);
							break;
						case 'c':
							dbInfo.className = args[i].substring(c + 2);
							break;
						case 's':
							dbInfo.service = args[i].substring(c + 2);
							break;
						case 't':
							dbInfo.table = args[i].substring(c + 2);
							break;
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
			catch(final Exception e)
			{
				System.err.println("Unable to connect to database: "+e.getMessage());
				return;
			}
		}
		final char[] exprCom=expr.toCharArray();
		if((!flags.contains(SEARCH_FLAG.CASESENSITIVE))||(fmt==FILE_FORMAT.HEX))
			for(int e=0;e<exprCom.length;e++)
				exprCom[e]=Character.toUpperCase(exprCom[e]);
		if(fmt==FILE_FORMAT.HEX)
		{
			for(int e=0;e<exprCom.length;e++)
				if((exprCom[e]!='?')&&(exprCom[e]!='%')&&(HEX_DIG.indexOf(exprCom[e])<0))
				{
					System.err.println("Illegal hex '"+exprCom[e]+"' in expression.");
					return;
				}
		}
		final File F=new File(path);
		final TreeSet<File> pathsDone = new TreeSet<File>();
		if(F.isDirectory())
		{
			final File[] files=F.listFiles();
			for(int f=0;f<files.length;f++)
				searchFilesystem(files[f],exprCom,flags,fmt,dbInfo,pfilter,pathsDone);
		}
		else
			searchFilesystem(F,exprCom,flags,fmt, dbInfo,pfilter,pathsDone);
	}
}
