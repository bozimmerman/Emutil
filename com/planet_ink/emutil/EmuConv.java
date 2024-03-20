package com.planet_ink.emutil;
import java.io.*;
import java.util.*;

import com.planet_ink.emutil.CBMDiskImage.FileInfo;
import com.planet_ink.emutil.CBMDiskImage.FileType;

/*
Copyright 2023-2023 Bo Zimmerman

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
public class EmuConv
{

	protected static final void showHelp()
	{
		System.out.println("EmuConv v"+D64Base.EMUTIL_VERSION+" (c)2023-"+D64Base.EMUTIL_AUTHOR);
		System.out.println("");
		System.out.println("USAGE: ");
		System.out.println("  CBMFileMod (-r) <src path/mask> <target type>");
		System.out.println("TARGET TYPES:");
		System.out.println("  RAW (.prg/.seq)");
		System.out.println("  P00 (.p00/.s00)");
		System.out.println("");
	}

	public static enum ModType
	{
		RAW,
		P00
	}

	public static boolean matches(final File F, final String mask)
	{
		final String name = F.getName();
		int ndex = 0;
		for(int i=0;i<mask.length();i++)
		{
			if(mask.charAt(i)=='?')
			{
				if(ndex>=mask.length())
					return false;
				ndex++;
			}
			else
			if(mask.charAt(i)=='*')
			{
				if(i==mask.length()-1)
					return true;
				final StringBuilder nextMatch = new StringBuilder("");
				int ii;
				for(ii=i+1;ii<mask.length();ii++)
				{
					if((mask.charAt(ii)=='*')||(mask.charAt(ii)=='?'))
						break;
					nextMatch.append(mask.charAt(ii));
				}
				if(nextMatch.length()>0)
				{
					if(ii==mask.length())
						return name.toUpperCase().endsWith(nextMatch.toString().toUpperCase());
					final int x = name.toUpperCase().indexOf(nextMatch.toString().toUpperCase(),ndex);
					if(x<0)
						return false;
					ndex=x+nextMatch.length();
				}
			}
			else
			{
				if(ndex>=mask.length())
					return false;
				if(Character.toUpperCase(mask.charAt(i))!=Character.toUpperCase(name.charAt(ndex)))
					return false;
			}
		}
		return true;
	}

	public static void main(final String[] args)
	{
		final List<String> largs=new ArrayList<String>(args.length);
		final Map<String,String> parms=new Hashtable<String,String>();
		largs.addAll(Arrays.asList(args));
		for(int i=0;i<largs.size();i++)
		{
			final String s=largs.get(i);
			if((!s.startsWith("-"))||(s.length()<2))
				break;
			if(Character.toLowerCase(s.charAt(1))=='r')
			{
				parms.put("R","R");
				largs.remove(i);
				i--;
			}
		}
		if(largs.size()<2)
		{
			showHelp();
			return;
		}
		final String srcPathMask = largs.get(0);
		final String targType=largs.get(1);
		ModType mTyp=ModType.RAW;
		try
		{
			mTyp = ModType.valueOf(targType.toUpperCase().trim());
		}
		catch(final Exception e)
		{
			System.err.println("Bad target type: "+targType);
			showHelp();
			System.exit(-1);
		}
		File srcPathF;
		String fileMask="*";
		final File chkF = new File(srcPathMask);
		if(chkF.exists())
		{
			if(chkF.isDirectory())
				srcPathF=chkF;
			else
			{
				srcPathF=chkF.getParentFile();
				fileMask = chkF.getName();
			}
		}
		else
		{
			final int x = srcPathMask.lastIndexOf(File.separator);
			if(x<0)
			{
				srcPathF=new File(".");
				fileMask=srcPathMask;
			}
			else
			{
				srcPathF=new File(srcPathMask.substring(0,x));
				if(!srcPathF.exists())
				{
					System.err.println("Bad path: "+srcPathMask.substring(0,x));
					System.exit(-1);
				}
				fileMask=srcPathMask.substring(x+1);
			}
		}
		final Stack<File> dirs = new Stack<File>();
		dirs.push(srcPathF);
		while(dirs.size()>0)
		{
			final File path = dirs.pop();
			for(final File F : path.listFiles())
			{
				if(F.isDirectory())
					dirs.push(F);
				else
				if(matches(F,fileMask))
				{
					final FileInfo inf = new FileInfo();
					if(F.getName().toLowerCase().endsWith(".seq")
					||(F.getName().toLowerCase().endsWith(".prg")))
					{
						if(F.getName().toLowerCase().endsWith(".prg"))
							inf.fileType=FileType.PRG;
						else
							inf.fileType=FileType.SEQ;
						try
						{
							inf.fileName = F.getName().substring(0,F.getName().length()-4).trim();
							inf.rawFileName = inf.fileName.getBytes("US-ASCII");
							for(int i=0;i<inf.rawFileName.length;i++)
								inf.rawFileName[i]=(byte)(D64Base.ascToPetTable[inf.rawFileName[i]] & 0xff);
							inf.data=new byte[(int)F.length()];
							final FileInputStream fin = new FileInputStream(F);
							int r = fin.read(inf.data,0,(int)F.length());
							while(r<F.length())
								r += fin.read(inf.data,r,(int)(F.length()-r));
							fin.close();
						}
						catch(final Exception e)
						{
							e.printStackTrace();
						}
					}
					else
					if(F.getName().toLowerCase().endsWith(".s00")
					||(F.getName().toLowerCase().endsWith(".p00")))
					{
						if(F.getName().toLowerCase().endsWith(".p00"))
							inf.fileType=FileType.PRG;
						else
							inf.fileType=FileType.SEQ;
						final int dlen = (int)F.length()-26;
						FileInputStream fin = null;
						try
						{
							fin = new FileInputStream(F);
							final byte[] buf=new byte[26];
							fin.read(buf, 0, 7);
							final String h1 = new String(buf,0,7);
							if((!h1.equals("C64File"))
							||(fin.read() != 0))
							{
								fin.close();
								throw new Exception("Unknown file type: "+F.getAbsolutePath());
							}
							fin.read(buf,0,16);
							int last=0;
							for(last=0;last<buf.length;last++)
							{
								if(buf[last]==0)
									break;
							}
							if((fin.read() != 0)||(fin.read() != 0))
							{
								fin.close();
								throw new Exception("Unknown file type: "+F.getAbsolutePath());
							}
							inf.rawFileName=Arrays.copyOf(buf, last);
							inf.fileName=F.getName().substring(0,F.getName().length()-4).trim();
							inf.data=new byte[dlen];
							int r = fin.read(inf.data,0,dlen);
							while(r<F.length())
								r += fin.read(inf.data,r,dlen-r);
							fin.close();
						}
						catch(final Exception e)
						{
							e.printStackTrace();
						}
						finally
						{
							if(fin != null)
							{
								try
								{
									fin.close();
								}
								catch (final IOException e)
								{
								}
							}
						}
					}
					else
					{
						System.out.println("Skipping unknown file: "+F.getAbsolutePath());
						continue;
					}
					F.delete();
					if(mTyp == ModType.RAW)
					{
						int app=0;
						File newF=new File(F.getParentFile(), inf.fileName + "." + inf.fileType.name().toLowerCase());
						while(newF.exists())
						{
							app++;
							newF=new File(F.getParentFile(), inf.fileName + "_"+app+"." + inf.fileType.name().toLowerCase());
						}
						try
						{
							final FileOutputStream fo = new FileOutputStream(newF);
							fo.write(inf.data);
							fo.close();
							System.out.println("Wrote "+newF.getAbsolutePath());
						}
						catch (final Exception e)
						{
							e.printStackTrace();
						}
					}
					else
					if(mTyp == ModType.P00)
					{
						int app=0;
						File newF=new File(F.getParentFile(), inf.fileName + "." + inf.fileType.name().toLowerCase().charAt(0)+"00");
						while(newF.exists())
						{
							app++;
							newF=new File(F.getParentFile(), inf.fileName + "_"+app+"." + inf.fileType.name().toLowerCase().charAt(0)+"00");
						}
						try
						{
							final FileOutputStream fo = new FileOutputStream(newF);
							fo.write("C64File".getBytes("US-ASCII"));
							fo.write(0);
							for(int i=0;i<16;i++)
							{
								if(i<inf.rawFileName.length)
									fo.write(inf.rawFileName[i]);
								else
									fo.write(0);
							}
							fo.write(0);
							fo.write(0);
							fo.write(inf.data);
							fo.close();
							System.out.println("Wrote "+newF.getAbsolutePath());
						}
						catch (final Exception e)
						{
							e.printStackTrace();
						}
					}
				}
			}
		}
	}
}
