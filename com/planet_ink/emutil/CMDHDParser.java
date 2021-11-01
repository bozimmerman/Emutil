package com.planet_ink.emutil;
import java.io.*;
import java.util.*;
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
public class CMDHDParser
{
	public CMDHDParser()
	{
	}

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
	private static void instructOut()
	{
		System.out.println("CMDHDParser v"+D64Base.EMUTIL_VERSION+" (c)2016-"+D64Base.EMUTIL_AUTHOR);
		System.out.println("");
		System.out.println("USAGE: ");
		System.out.println("  CMDHDParser LIST <CMDHD_RAWIMAGE>");
		System.out.println("  CMDHDParser EXTRACT <CMDHD_RAWIMAGE> <EXTRACT_PATH_OR_FILENAME> <CMDHD_PARTITION_NUMBER>");
		System.out.println("  CMDHDParser EXTRACT <CMDHD_RAWIMAGE> <EXTRACT_PATH> ALL");
		System.out.println("  CMDHDParser REPLACE <CMDHD_RAWIMAGE> <NEW_PART_IMAGE_FILE> <CMDHD_PARTITION_NUMBER>");
		System.out.println("  CMDHDParser REPLACE <CMDHD_RAWIMAGE> <NEW_PART_IMAGES_PATH> ALL");
		System.out.println("");
		System.exit(-1);
	}

	private static boolean isInteger(final String s)
	{
		try
		{
			Integer.parseInt(s);
			return true;
		}
		catch(final Exception e)
		{
			return false;
		}
	}

	private static int toInteger(final String s)
	{
		try
		{
			return Integer.parseInt(s);
		}
		catch(final Exception e)
		{
			return 0;
		}
	}

	private static String padRight(String s, final int pad)
	{
		while(s.length()<pad)
			s+=" ";
		return s;
	}

	private static char convertToPetscii(final byte b)
	{
		if(b<65) return (char)b;
		if(b<91) return Character.toLowerCase((char)b);
		if(b<192) return (char)b;
		if(b<219) return Character.toUpperCase((char)(b-128));
		return (char)(b-128);
	}

	public static short unsigned(final byte b)
	{
		return (short)(0xFF & b);
	}

	public static String toHex(final byte b)
	{
		return HEX[unsigned(b)];
	}

	public static String toHex(long l)
	{
		String hex="";
		while(l>0)
		{
			hex=toHex((byte)(l % 256L))+hex;
			l=l>>8L;
		}
		return hex;
	}

	public static String toHex(final byte[] buf)
	{
		final StringBuffer ret=new StringBuffer("");
		for(int b=0;b<buf.length;b++)
			ret.append(toHex(buf[b]));
		return ret.toString();
	}

	private static class PartInfo
	{
		int number=-1;
		String name="";
		@SuppressWarnings("unused")
		int type=-1;
		String typeName="";
		long startAddr=-1;
		long len=-1;
	}

	@SuppressWarnings("serial")
	private static class CMDHDParseException extends Exception
	{
		public CMDHDParseException(final String msg)
		{
			super(msg);
		}
	}

	public static List<PartInfo> getPartInfo(final RandomAccessFile hdR) throws IOException,CMDHDParseException
	{
		final List<PartInfo> list=new LinkedList<PartInfo>();
		long pos = 262144;
		hdR.seek(pos);
		if((hdR.read()!=1)||(hdR.read()!=1))
		{
			throw new CMDHDParseException("CMD HD Raw Image Invalid (1-1 check)!");
		}
		pos+=2;
		for(int part=0;part<256;part++)
		{
			hdR.seek(pos + (part * 32));
			final byte[] partEntry = new byte[32];
			hdR.readFully(partEntry);
			final int partType = unsigned(partEntry[0]);
			if(partType ==0)
				continue;
			final PartInfo info = new PartInfo();
			info.number=part;
			info.type=partType;
			if((partType > 5)&&(partType < 255))
			{
				throw new CMDHDParseException("CMD HD Raw Image Invalid at partition "+part+": (part type "+partType+")!");
			}
			switch(partType)
			{
			case 1:
				info.typeName="DNP";
				break;
			case 2:
				info.typeName="D64";
				break;
			case 3:
				info.typeName="D71";
				break;
			case 4:
				info.typeName="D81";
				break;
			case 5:
				info.typeName="D81";
				break;
			case 255:
				info.typeName="SYS";
				break;
			}
			if(partEntry[1]!=0)
			{
				throw new CMDHDParseException("CMD HD Raw Image Invalid (0 check)!");
			}
			final byte[] rawPartName = Arrays.copyOfRange(partEntry, 3, 19);
			int partNameLen = 15;
			while((partNameLen>=0) && (unsigned(rawPartName[partNameLen])== 160))
				partNameLen--;
			for(int i=0;i<=partNameLen;i++)
				rawPartName[i]=(byte)convertToPetscii(rawPartName[i]);
			info.name= new String(rawPartName,0,partNameLen+1);
			info.startAddr = ((unsigned(partEntry[19]) * 65536L) + (unsigned(partEntry[20]) * 256L) + unsigned(partEntry[21])) * 512L;
			info.len = ((unsigned(partEntry[27]) * 65536L) + (unsigned(partEntry[28]) * 256L) + unsigned(partEntry[29])) * 512L;
			list.add(info);
		}
		return list;
	}

	public static void main(final String[] args)
	{
		if(args.length<2)
		{
			instructOut();
			return;
		}
		final String command = args[0];
		if(command.equalsIgnoreCase("LIST"))
		{
			final String hdFilename = args[1];
			final File hdF=new File(hdFilename);
			if((!hdF.exists())||hdF.isDirectory())
			{
				System.err.println("CMD HD Raw Image file not found!");
				System.exit(-1);
			}
			try
			{
				final RandomAccessFile hdR=new RandomAccessFile(hdF,"r");
				final List<PartInfo> list = getPartInfo(hdR);
				hdR.close();
				for(final PartInfo info : list)
				{
					System.out.print(padRight(""+info.number,3)+": ");
					System.out.print(info.typeName+" ");
					System.out.print(padRight(info.name,17));
					System.out.print("@"+padRight(toHex(info.startAddr),8));
					System.out.print(" Len:"+padRight(toHex(info.len),8));
					System.out.println();
				}
			}
			catch(final CMDHDParseException e)
			{
				System.err.println(e.getMessage());
				System.exit(-1);
			}
			catch(final Exception e)
			{
				e.printStackTrace();
				System.exit(-1);
			}
		}
		else
		if(command.equalsIgnoreCase("EXTRACT"))
		{
			if(args.length<3)
			{
				instructOut();
				return;
			}
			int partNum=-1;
			if(args.length==4)
			{
				if(!args[3].equalsIgnoreCase("ALL"))
				{
					if(!isInteger(args[3]))
					{
						System.err.println("Invalid partition number or 'all': "+args[3]+"!");
						instructOut();
						System.exit(-1);
					}
					partNum=toInteger(args[3]);
				}
			}
			final String hdFilename = args[1];
			final String targetPath = args[2];
			final File hdF=new File(hdFilename);
			final File targP=new File(targetPath);
			if((!hdF.exists())||hdF.isDirectory())
			{
				System.err.println("CMD HD Raw Image file not found!");
				System.exit(-1);
			}
			if((partNum<0)&&((!targP.exists())||(!targP.isDirectory())))
			{
				System.err.println("Target path not found or not directory!");
				System.exit(-1);
			}

			try
			{
				final RandomAccessFile hdR=new RandomAccessFile(hdF,"r");
				final List<PartInfo> list = getPartInfo(hdR);
				for(final PartInfo info : list)
				{
					if((partNum < 0)||(partNum == info.number))
					{
						final byte[] buf=new byte[(int)info.len];
						hdR.seek(info.startAddr);
						hdR.readFully(buf);
						File F=targP;
						if((partNum<0)||(targP.isDirectory()))
							F = new File(targP,info.number+"_"+info.name+"."+info.typeName.toLowerCase());
						final FileOutputStream fout = new FileOutputStream(F);
						fout.write(buf);
						fout.close();
						System.out.println("Wrote "+F.getAbsolutePath());
					}
				}
				System.out.println("Done.");
				hdR.close();
			}
			catch(final CMDHDParseException e)
			{
				System.err.println(e.getMessage());
				System.exit(-1);
			}
			catch(final Exception e)
			{
				e.printStackTrace();
				System.exit(-1);
			}
		}
		else
		if(command.equalsIgnoreCase("REPLACE"))
		{
			if(args.length<3)
			{
				instructOut();
				return;
			}
			int partNum=-1;
			if(args.length==4)
			{
				if(!args[3].equalsIgnoreCase("ALL"))
				{
					if(!isInteger(args[3]))
					{
						System.err.println("Invalid partition number or 'all': "+args[3]+"!");
						instructOut();
						System.exit(-1);
					}
					partNum=toInteger(args[3]);
				}
			}
			final String hdFilename = args[1];
			final String targetPath = args[2];
			final File hdF=new File(hdFilename);
			final File inP=new File(targetPath);
			if((!hdF.exists())||hdF.isDirectory())
			{
				System.err.println("CMD HD Raw Image file not found!");
				System.exit(-1);
			}
			if((partNum<0)&&((!inP.exists())||(!inP.isDirectory())))
			{
				System.err.println("Input path not found or not directory!");
				System.exit(-1);
			}

			try
			{
				final RandomAccessFile hdR=new RandomAccessFile(hdF,"rws");
				final List<PartInfo> list = getPartInfo(hdR);
				for(final PartInfo info : list)
				{
					if((partNum < 0)||(partNum == info.number))
					{
						final byte[] buf=new byte[(int)info.len];
						File F=inP;
						if((partNum<0)||(inP.isDirectory()))
							F = new File(inP,info.number+"_"+info.name+"."+info.typeName.toLowerCase());
						if((!F.exists())||F.isDirectory())
						{
							System.err.println("Partition Input Image '"+F.getAbsolutePath()+"' not found!");
							System.exit(-1);
						}
						if(F.length()!=info.len)
						{
							System.err.println("Partition Input Image '"+F.getAbsolutePath()+"' not value, because it must be "+info.len+" bytes!");
							System.exit(-1);
						}
						final RandomAccessFile fin = new RandomAccessFile(F,"r");
						fin.readFully(buf);
						fin.close();
						hdR.seek(info.startAddr);
						hdR.write(buf);
						System.out.println("Wrote "+F.getAbsolutePath()+" to position "+info.startAddr);
					}
				}
				System.out.println("Done.");
				hdR.close();
			}
			catch(final CMDHDParseException e)
			{
				System.err.println(e.getMessage());
				System.exit(-1);
			}
			catch(final Exception e)
			{
				e.printStackTrace();
				System.exit(-1);
			}
		}
		else
			instructOut();
	}
}
