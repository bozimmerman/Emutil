import java.io.*;
import java.util.*;
public class D64Search 
{
	// oliver netlib.64
	// todo: add file masks to options
	// www.paradroid.net/diskimage
	// usb byteblaster mv
	// altera free developers kit usbblaster
	public static final String D64IMAGE=".D64";
	public static final String D71IMAGE=".D71";
	public static final String D81IMAGE=".D81";
	public static final int FLAG_CASESENSITIVE=1;
	public static final int FLAG_VERBOSE=2;
	public static final int FLAG_RECURSE=4;
	public static final int FLAG_INSIDE=8;
	public static final int FMT_PETSCII=0;
	public static final int FMT_ASCII=1;
	public static final int FMT_HEX=2;
	public static final String[] IMAGES={D64IMAGE,D71IMAGE,D81IMAGE};
	public static final String[] HEX=new String[256];
	public static final Hashtable ANTI_HEX=new Hashtable();
	public static final String HEX_DIG="0123456789ABCDEF";
	static{
		for(int h=0;h<16;h++)
			for(int h2=0;h2<16;h2++)
			{
				HEX[(h*16)+h2]=""+HEX_DIG.charAt(h)+HEX_DIG.charAt(h2);
				ANTI_HEX.put(HEX[(h*16)+h2],new Short((short)((h*16)+h2)));
			}
	}
	
	private static int getImageSecsPerTrack(String type, int t)
	{
		if(type.equals(D64IMAGE))
		{
			if(t<18) return 21;
			if(t<25) return 19;
			if(t<31) return 18;
			return 17;
		}
		else
		if(type.equals(D71IMAGE))
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
		if(type.equals(D81IMAGE))
			return 40;
		return -1;
	}

	public static int getImageTotalBytes(String type)
	{
		int ts=getImageNumTracks(type);
		int total=0;
		for(int t=1;t<=ts;t++)
			total+=(256*getImageSecsPerTrack(type,t));
		return total;
	}
	
	
	public static int getImageDirTrack(String type)
	{
		if(type.equals(D64IMAGE))
			return 18;
		if(type.equals(D71IMAGE))
			return 18;
		if(type.equals(D81IMAGE))
			return 40;
		return -1;
	}
	
	private static int getImageNumTracks(String type)
	{
		if(type.equals(D64IMAGE))
			return 35;
		else
		if(type.equals(D71IMAGE))
			return 70;
		else
		if(type.equals(D81IMAGE))
			return 79;
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
	
	private static byte[][][] parseMap(String type, byte[] buf)
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
	
	public static byte[][][] getDisk(String type,File F)
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
	
	public static String toHex(byte b){ return HEX[b];}
	public static short fromHex(String hex){ return ((Short)ANTI_HEX.get(hex)).shortValue();}
	public static String getFileContent(byte[][][] tsmap, int t, int mt, int s, int fmt)
	{
		HashSet doneBefore=new HashSet();
		StringBuffer buf=new StringBuffer("");
		byte[] sector=null;
		while((t!=0)&&(!doneBefore.contains(tsmap[t][s]))&&(t<=mt))
		{
			int maxBytes=255;
			sector=tsmap[t][s];
			if(sector[0]==0) maxBytes=new Short(sector[1]).intValue();
			doneBefore.add(sector);
			for(int i=2;i<=maxBytes;i++)
				switch(fmt)
				{
				case FMT_PETSCII:
					buf.append(convertToPetscii(sector[i]));
					break;
				case FMT_ASCII:
					buf.append((char)sector[i]);
					break;
				case FMT_HEX:
					buf.append(toHex(sector[i]));
					break;
				}
			t=new Short(sector[0]).intValue();
			s=new Short(sector[1]).intValue();
		}
		return buf.toString();
	}
	
	public static Vector getFiledata(String type, byte[][][] tsmap, int flags, int fmt)
	{
		int t=getImageDirTrack(type);
		int maxT=D64Search.getImageNumTracks(type);
		int s=0;
		Vector finalData=new Vector();
		Vector fileNames=new Vector();
		Vector types=((flags&FLAG_VERBOSE)>0)?new Vector():null;
		Vector sizes=((flags&FLAG_VERBOSE)>0)?new Vector():null;
		Vector data=((flags&FLAG_INSIDE)>0)?new Vector():null;
		boolean inside=((flags&FLAG_INSIDE)>0);
		byte[] sector=tsmap[t][s];
		t=sector[0];
		s=sector[1];
		HashSet doneBefore=new HashSet();
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
				if((fmt==FMT_PETSCII)||inside)
					for(int x=i+3;x<=fn;x++)
						file.append(convertToPetscii(sector[x]));
				else
				if(fmt==FMT_ASCII)
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
						int size=(256*(sector[i+28]+(256*sector[i+29])));
						sizes.add(new Integer(size).toString());
					}
					if(types!=null)
						switch(sector[i])
						{
						case (byte)129: types.add(",s"); break;
						case (byte)130: types.add(",p"); break;
						case (byte)131: types.add(",u"); break;
						case (byte)132: types.add(",r"); break;
						default: types.add(",?");break;
						}
					if(data!=null)
					{
						int fileT=new Short(sector[i+1]).intValue();
						int fileS=new Short(sector[i+2]).intValue();
						data.addElement(getFileContent(tsmap,fileT,maxT,fileS,fmt));
					}
				}
			}
			t=new Short(sector[0]).intValue();
			s=new Short(sector[1]).intValue();
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
			finalData.addElement(toStringList(data));
		else
			finalData.addElement(new String[fileNames.size()][]);
		return finalData;
	}

	private static String[] toStringList(Vector V)
	{
		String[] files=new String[V.size()];
		for(int f=0;f<V.size();f++)
			files[f]=(String)V.elementAt(f);
		return files;
	}
	private static byte[][] toByteList(Vector V)
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
		if((expr.length>1)&&(expr[0]=='*'))
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
			case '*':
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

	private static boolean checkInside(String buf, char[] expr, int flags, int fmt, boolean caseSensitive)
	{
		if(!caseSensitive) buf=buf.toUpperCase();
		int firstLetter=0;
		for(firstLetter=0;firstLetter<expr.length;firstLetter++)
			if((expr[firstLetter]!='?')&&(expr[firstLetter]!='*'))
				break;
		if(firstLetter>=expr.length) return true;
		if(fmt==FMT_HEX)
		{
			if(firstLetter>(expr.length-2)) return true;
			int i=buf.indexOf(expr[firstLetter]+""+expr[firstLetter+1]);
			while((i>=0)&&(i+expr.length<buf.length()))
			{
				if((i%2)==0)
				{
					String substr=buf.substring(i,i+expr.length);
					if(check(substr,expr,caseSensitive))
						return true;
				}
				i=buf.indexOf(expr[firstLetter]+""+expr[firstLetter+1],i+2);
			}
			return false;
		}
		
		return false;
	}
	
	private static void search(File F, char[] expr, int flags, int fmt)
	{
		if(F.isDirectory())
		{
			File[] files=F.listFiles();
			for(int f=0;f<files.length;f++)
				search(files[f],expr,flags,fmt);
		}
		else
		{
			boolean caseSensitive=(flags&FLAG_CASESENSITIVE)>0;
			boolean inside=(flags&FLAG_INSIDE)>0;
			for(int f=0;f<IMAGES.length;f++)
				if(F.getName().toUpperCase().endsWith(IMAGES[f]))
				{
					String type=IMAGES[f];
					byte[][][] disk=getDisk(type,F);
					Vector fileData=getFiledata(type,disk,flags,fmt);
					String[] names=(String[])fileData.firstElement();
					if(names.length>0)
					{
						boolean announced=false;
						for(int n=0;n<names.length;n++)
							if((inside&&(checkInside(((String[])fileData.lastElement())[n],expr,flags,fmt,caseSensitive)))
							||check(caseSensitive?names[n]:names[n].toUpperCase(),expr,caseSensitive))
							{
								if(!announced)
								{
									System.out.println(F.getName());
									announced=true;
								}
								String name=names[n];
								if((flags&FLAG_INSIDE)==0)
								{
									if(fmt==FMT_ASCII)
									{
										StringBuffer newName=new StringBuffer("");
										for(int x=0;x<name.length();x++)
											newName.append(D64Search.convertToPetscii((byte)name.charAt(x)));
										name=newName.toString();
									}
									else
									{
										StringBuffer newName=new StringBuffer("");
										for(int x=0;x<name.length();x+=2)
											newName.append((char)fromHex(name.substring(0,2)));
										name=newName.toString();
									}
								}
								if((flags&FLAG_VERBOSE)>0)
								{
									String[] types=(String[])fileData.elementAt(1);
									String[] sizes=(String[])fileData.elementAt(2);
									System.out.println("  "+names[n]+types[n]+" ("+sizes[n]+")");
								}
								else
									System.out.println("  "+names[n]);
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
			System.out.println("  -C case sensitive");
			System.out.println("  -X expr format (-Xp=petscii, Xa=ascii, Xh=hex)");
			System.out.println("  -I search inside files");
			System.out.println("\n\r\n\r* You might have to put \"*\" expressions in quotes.");
			System.out.println("* Searches inside files allow ?, but not \"*\" expressions.");
			return;
		}
		int flags=0;
		int fmt=-1;
		String path=null;
		String expr="";
		for(int i=0;i<args.length;i++)
		{
			if((args[i].startsWith("-")&&(path==null)))
			{
				for(int c=1;i<args[i].length();c++)
				{
					switch(args[i].charAt(c))
					{
					case 'r':
					case 'R': flags=flags|FLAG_RECURSE; break;
					case 'c':
					case 'C': flags=flags|FLAG_CASESENSITIVE; break;
					case 'v':
					case 'V': flags=flags|FLAG_VERBOSE; break;
					case 'i':
					case 'I': flags=flags|FLAG_INSIDE; break;
					case 'x':
					case 'X':
						fmt=(c<args[i].length()-1)?"PAH".indexOf(Character.toUpperCase(args[i].charAt(c+1))):-1;
						if(fmt<0)
						{
							System.out.println("Error: -x  must be followed by a,p,or h");
							return;
						}
						break;
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
		if((expr.length()>1)&&(expr.startsWith("*"))
		&&((expr.charAt(1)=='*')||(expr.charAt(1)=='?')))
		{
			System.out.println("illegal *? expression error.");
			return;
		}
		if(path==null)
		{
			System.out.println("Path not found!");
			return;
		}
		char[] exprCom=expr.toCharArray();
		if(((flags&FLAG_CASESENSITIVE)==0)||(fmt==FMT_HEX))
			for(int e=0;e<exprCom.length;e++)
				exprCom[e]=Character.toUpperCase(exprCom[e]);
		if(fmt==FMT_HEX)
			for(int e=0;e<exprCom.length;e++)
				if((exprCom[e]!='?')&&(exprCom[e]!='*')&&(HEX_DIG.indexOf(exprCom[e])<0))
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
