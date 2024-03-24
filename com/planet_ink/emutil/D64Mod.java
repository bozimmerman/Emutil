package com.planet_ink.emutil;
import java.io.*;
import java.util.*;

import com.planet_ink.emutil.CBMDiskImage.BAMBack;
import com.planet_ink.emutil.CBMDiskImage.BAMInfo;
import com.planet_ink.emutil.CBMDiskImage.FileInfo;
import com.planet_ink.emutil.CBMDiskImage.FileType;
import com.planet_ink.emutil.CBMDiskImage.ImageType;
import com.planet_ink.emutil.CBMDiskImage.TrackSec;

/*
Copyright 2017-2024 Bo Zimmerman

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
public class D64Mod extends D64Base
{
	enum Action
	{
		SCRATCH, EXTRACT, INSERT, BAM, LIST, DIR, LYNX, CHECK, FIX
	}

	enum BamAction
	{
		CHECK, ALLOC, FREE
	}


	public static void imageError(final String msg, final boolean cont)
	{
		System.err.println(msg);
		if(!cont)
			System.exit(-1);
	}

	public enum D64FormatFlag
	{
		PRGSEQ
	}

	public static int writeLNX(final List<FileInfo> info, final OutputStream out) throws IOException
	{
		final int[] lynxHeader = new int[] {
			0x01, 0x08, 0x5B, 0x08, 0x0A, 0x00, 0x97, 0x35, /* .......5 */
			0x33, 0x32, 0x38, 0x30, 0x2C, 0x30, 0x3A, 0x97, /* 3280,0:. */
			0x35, 0x33, 0x32, 0x38, 0x31, 0x2C, 0x30, 0x3A, /* 53281,0: */
			0x97, 0x36, 0x34, 0x36, 0x2C, 0xC2, 0x28, 0x31, /* .646,.(1 */
			0x36, 0x32, 0x29, 0x3A, 0x99, 0x22, 0x93, 0x11, /* 62):.".. */
			0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x22, /* ......." */
			0x3A, 0x99, 0x22, 0x20, 0x20, 0x20, 0x20, 0x20, /* :."      */
			0x55, 0x53, 0x45, 0x20, 0x4C, 0x59, 0x4E, 0x58, /* USE LYNX */
			0x20, 0x54, 0x4F, 0x20, 0x44, 0x49, 0x53, 0x53, /*  TO DISS */
			0x4F, 0x4C, 0x56, 0x45, 0x20, 0x54, 0x48, 0x49, /* OLVE THI */
			0x53, 0x20, 0x46, 0x49, 0x4C, 0x45, 0x22, 0x3A, /* S FILE": */
			0x89, 0x31, 0x30, 0x00, 0x00, 0x00, 0x0D
		};
		final int[] lynxSig = new int[] {
			0x2A, 0x4C, 0x59, 0x4E, 0x58, 0x20, 0x41, 0x52, /* *LYNX AR */
			0x43, 0x48, 0x49, 0x56, 0x45, 0x20, 0x42, 0x59, /* CHIVE BY */
			0x20, 0x50, 0x4F, 0x57, 0x45, 0x52, 0x32, 0x30, /*  POWER20 */
			0x0D
		};
		final List<FileInfo> finfo = new ArrayList<FileInfo>(info.size());
		for(final FileInfo F : info)
		{
			if((F.rawFileName.length>0)
			&&(F.data!=null)
			&&(F.data.length>0))
				finfo.add(F);
		}
		if(finfo.size()==0)
			throw new IOException("No legitimate files found.");
		int bytesWritten = 0;
		final ByteArrayOutputStream unpaddedHeader = new ByteArrayOutputStream();
		{
			int headerSize = 0;
			final List<byte[]> dirEntries = new ArrayList<byte[]>();
			for(final FileInfo F : finfo)
			{
				final ByteArrayOutputStream bout = new ByteArrayOutputStream();
				for(int i=0;i<16;i++)
				{
					if(i<F.rawFileName.length)
						bout.write(F.rawFileName[i]);
					else
						bout.write((byte)160);
				}
				bout.write((byte)13);

				final int numBlocks = (int)Math.round(Math.ceil(F.data.length / 254.0));
				final int extraBytes = F.data.length % 254;
				bout.write((byte)32);
				final String numBlksStr = (""+numBlocks);
				bout.write(numBlksStr.getBytes("US-ASCII"));
				bout.write((byte)13);

				bout.write((byte)(F.fileType.name().toUpperCase().charAt(0))); // lowercase petscii char?
				bout.write((byte)13);

				bout.write((byte)32);
				final String exBytesStr = (""+(extraBytes+1)); //TODO: WHY THIS?!?!?!????!!!
				bout.write(exBytesStr.getBytes("US-ASCII"));
				bout.write((byte)32);
				bout.write((byte)13);

				dirEntries.add(bout.toByteArray());
				headerSize += bout.toByteArray().length;
			}
			final byte[] numEntries;
			{
				final ByteArrayOutputStream bout = new ByteArrayOutputStream();
				bout.write((byte)32);
				final String numEntStr = (""+dirEntries.size());
				bout.write(numEntStr.getBytes("US-ASCII"));
				for(int i=numEntStr.length();i<3;i++)
					bout.write(32);
				bout.write((byte)13);
				numEntries = bout.toByteArray();
			}
			headerSize += numEntries.length;
			headerSize += lynxHeader.length + 4 + lynxSig.length;
			final int numBlocksNeeded = (int)Math.round(Math.ceil((double)headerSize / (double)254));
			final byte[] numHeaderBlocks;
			{
				final ByteArrayOutputStream bout = new ByteArrayOutputStream();
				bout.write((byte)32);
				final String numBlksStr = ""+numBlocksNeeded;
				bout.write(numBlksStr.getBytes("US-ASCII"));
				for(int i=numBlksStr.length();i<3;i++)
					bout.write((byte)32);
				numHeaderBlocks = bout.toByteArray();
			}

			//*** now build the final header ***
			for(int i=0;i<lynxHeader.length;i++)
				unpaddedHeader.write((byte)(lynxHeader[i] & 0xff));
			unpaddedHeader.write(numHeaderBlocks);
			for(int i=0;i<lynxSig.length;i++)
				unpaddedHeader.write((byte)(lynxSig[i] & 0xff));
			unpaddedHeader.write(numEntries);
			for(final byte[] dirEntry : dirEntries)
				unpaddedHeader.write(dirEntry);
		}
		//** write the header
		final int numPaddingBytes = 254 - (unpaddedHeader.size() % 254);
		bytesWritten += unpaddedHeader.size() + numPaddingBytes;
		out.write(unpaddedHeader.toByteArray());
		unpaddedHeader.reset();
		for(int i=0;i<numPaddingBytes;i++)
			out.write(0);
		for(int f=0;f<finfo.size();f++)
		{
			final FileInfo F = finfo.get(f);
			final int extraBytes = 254 - (F.data.length % 254);
			out.write(F.data);
			bytesWritten += F.data.length;
			if(f<finfo.size()-1)
			{
				bytesWritten += extraBytes;
				for(int i=0;i<extraBytes;i++)
					out.write(0);
			}
		}
		out.flush();
		return bytesWritten;
	}

	protected static final void showHelp()
	{
		System.out.println("D64Mod v"+EMUTIL_VERSION+" (c)2017-"+EMUTIL_AUTHOR);
		System.out.println("");
		System.out.println("USAGE: ");
		System.out.println("  D64Mod (-r -q) <image file> <action> <action arguments>");
		System.out.println("ACTIONS:");
		System.out.println("  SCRATCH <file>");
		System.out.println("  EXTRACT (-p) <file> <target path>");
		System.out.println("  INSERT <source path> <file>");
		System.out.println("  CHECK");
		System.out.println("  FIX");
		System.out.println("  BAM CHECK");
		System.out.println("  BAM ALLOC (Checks for sectors that need bam alloc)");
		System.out.println("  BAM FREE (Checks for sectors that need bam free)");
		System.out.println("  LIST/DIR <PATH>");
		System.out.println("  LIST/DIR ALL");
		System.out.println("  LYNX <file> <target file>");
		System.out.println("");
	}

	public static void main(final String[] args)
	{
		final BitSet parseFlags = new BitSet();
		final Set<D64FormatFlag> fmtFlags = new HashSet<D64FormatFlag>();
		final List<String> largs=new ArrayList<String>(args.length);
		largs.addAll(Arrays.asList(args));
		for(int i=0;i<largs.size();i++)
		{
			final String s=largs.get(i);
			if((!s.startsWith("-"))||(s.length()<2))
				break;
			if(Character.toLowerCase(s.charAt(1))=='r')
			{
				parseFlags.set(PF_RECURSE);
				largs.remove(i);
				i--;
			}
			else
			if(Character.toLowerCase(s.charAt(1))=='q')
			{
				parseFlags.set(PF_NOERRORS);
				largs.remove(i);
				i--;
			}
		}
		if(largs.size()>3)
		{
			for(int i=2;i<largs.size();i++)
			{
				final String s=largs.get(i);
				if((!s.startsWith("-"))||(s.length()<2))
					break;
				if(Character.toLowerCase(s.charAt(1))=='p')
				{
					fmtFlags.add(D64FormatFlag.PRGSEQ);
					largs.remove(i);
					i--;
				}
			}
		}
		if(largs.size()<2)
		{
			showHelp();
			return;
		}
		final String imagePath=largs.get(0);
		final String actionStr=largs.get(1);
		Action action = Action.EXTRACT;
		try
		{
			action=Action.valueOf(actionStr.toUpperCase().trim());
		}
		catch(final Exception e)
		{
			System.err.println("Invalid action: "+actionStr);
			System.exit(-1);
		}
		String imageFileStr;
		if(largs.size()<3)
		{
			if((action != Action.CHECK)
			&&(action != Action.FIX))
			{
				showHelp();
				return;
			}
			else
				imageFileStr = "";
		}
		else
			imageFileStr = largs.get(2);
		String expr="";
		expr=expr.trim();
		final List<IOFile> imageFiles = new ArrayList<IOFile>();
		final IOFile chkF=new IOFile(new File(imagePath));
		if(chkF.isDirectory())
		{
			final LinkedList<IOFile> dirs = new LinkedList<IOFile>();
			dirs.add(chkF);
			while(dirs.size()>0)
			{
				final IOFile dirF = dirs.removeFirst();
				for(final IOFile F : dirF.listFiles())
				{
					if(F.isDirectory()
					&& (parseFlags.get(PF_RECURSE)||(F.getName().toLowerCase().endsWith(".zip"))))
					{
						dirs.add(F);
						continue;
					}

					if(F.isFile()&&F.exists()&&F.canRead())
					{
						if(CBMDiskImage.getImageTypeAndGZipped(F)!=null)
							imageFiles.add(F);
					}
				}
				try
				{
					dirF.close();
				}
				catch (final IOException e)
				{
					e.printStackTrace();
				}
			}
			if(imageFiles.size()==0)
			{
				System.err.println("no disk images found in : "+imagePath);
				System.exit(-1);
			}
		}
		else
		{
			if((!chkF.isFile())||(!chkF.exists())||(!chkF.canRead()))
			{
				System.err.println("image not found: "+imagePath);
				System.exit(-1);
			}
			final ImageType imagetype = CBMDiskImage.getImageTypeAndGZipped(chkF);
			if((imagetype == null)
			&&((action!=Action.DIR)
				||((!chkF.getName().toLowerCase().endsWith(".lnx"))&&(!chkF.getName().toLowerCase().endsWith(".lnx.gz")))))
			{
				System.err.println("File is not an image: "+imagePath);
				System.exit(-1);
			}
			imageFiles.add(chkF);
		}
		BamAction bamAction = BamAction.CHECK;
		String localFileStr="";
		switch(action)
		{
		case SCRATCH:
			if(largs.size()<2)
			{
				System.err.println("Missing target file(s)");
				System.exit(-1);
			}
			imageFileStr = largs.get(2);
			break;
		case INSERT:
			if(largs.size()<4)
			{
				System.err.println("Missing target file");
				System.exit(-1);
			}
			//System.err.println("Not implemented");
			//System.exit(-1);
			localFileStr = largs.get(2);
			imageFileStr = largs.get(3);
			break;
		case EXTRACT:
			if(args.length<4)
			{
				System.err.println("Missing target file");
				System.exit(-1);
			}
			localFileStr = largs.get(3);
			break;
		case LYNX:
			if(args.length<4)
			{
				System.err.println("Missing target file");
				System.exit(-1);
			}
			localFileStr = largs.get(3);
			break;
		case BAM:
			try
			{
				bamAction=BamAction.valueOf(largs.get(2).toUpperCase().trim());
			}
			catch(final Exception e)
			{
				System.err.println("Invalid sub-command");
				System.exit(-1);
			}
			break;
		case CHECK:
		case FIX:
			break;
		case DIR:
		case LIST:
			/*
			if(args.length<4)
			{
				System.err.println("Missing target file");
				System.exit(-1);
			}
			*/
			imageFileStr = largs.get(2);
			break;
		}
		for(final IOFile imageF : imageFiles)
		{
			final CBMDiskImage disk = new CBMDiskImage(imageF);
			final byte[][][] diskBytes = disk.getDiskBytes();
			final List<FileInfo> files = disk.getFiles(parseFlags);
			FileInfo file = disk.findFile(imageFileStr,false,parseFlags);
			if(file == null)
				file = disk.findFile(imageFileStr,true,parseFlags);
			if((action == Action.LIST)
			||(action == Action.DIR)
			||(action == Action.LYNX))
			{
				if((!imageFileStr.equalsIgnoreCase("ALL")) && (file == null))
				{
					imageError("No such path exists in image: "+imageFileStr,imageFiles.size()>0);
					continue;
				}
			}
			else
			if(action == Action.INSERT)
			{
				// that's ok.. anything is OK for insert, really.
			}
			else
			if((action != Action.BAM)
			&&(action != Action.CHECK)
			&& (file == null))
			{
				imageError("File not found in image: "+imageFileStr,imageFiles.size()>0);
				continue;
			}
			else
			if((action == Action.BAM)
			&&(disk.getType() == ImageType.T64))
			{
				imageError("No BAM action possible on: "+imageFileStr,imageFiles.size()>0);
				continue;
			}
			final LinkedList<FileInfo> fileList=new LinkedList<FileInfo>();
			if((file != null)
			&&(action != Action.INSERT))
			{
				fileList.add(file);
				if((file.fileType == FileType.DIR)||(file.fileType == FileType.CBM))
				{
					final LinkedList<FileInfo> notdone=new LinkedList<FileInfo>();
					notdone.add(file);
					while(notdone.size()>0)
					{
						final FileInfo dir = notdone.removeFirst();
						for(final FileInfo i : files)
						{
							if(i.parentF == dir)
							{
								if((i.fileType == FileType.DIR)||(i.fileType == FileType.CBM))
									notdone.add(i);
								fileList.add(i);
							}
						}
					}
				}
			}

			final boolean[] rewriteD64 = {false};
			switch(action)
			{
			case SCRATCH:
			{
				while(fileList.size()>0)
				{
					final FileInfo f = fileList.removeLast();
					try
					{
						if(disk.scratchFile(f))
						{
							System.out.println("Scratched "+f.filePath);
							rewriteD64[0]=true;
						}
					}
					catch(final Exception e)
					{
						imageError(e.getMessage(),imageFiles.size()>0);
						continue;
					}
				}
				break;
			}
			case EXTRACT:
			{
				final String finalDirName = localFileStr.replaceAll("\\*", imageF.getName());
				final int x=localFileStr.lastIndexOf(File.separator);
				File localFileF;
				if(x>0)
					localFileF=new File(new File(finalDirName.substring(0,x)),finalDirName.substring(x+1).replaceAll("/", "_"));
				else
					localFileF=new File(imageF.getParent(),finalDirName.replaceAll("/", "_"));
				if(!finalDirName.equals(localFileStr) && (!localFileF.exists()))
					localFileF.mkdirs();
				if((fileList.size()>1)&&(!localFileF.isDirectory()))
				{
					imageError(localFileStr+" needs to be a directory to extract "+fileList.size()+" files.",imageFiles.size()>0);
					continue;
				}
				while(fileList.size()>0)
				{
					final FileInfo f = fileList.removeLast();
					final byte[] fileData = f.data;
					if(fileData == null)
					{
						if(!localFileF.isDirectory())
							System.out.println("No data found in "+f.fileName);
					}
					else
					{
						try
						{
							FileOutputStream fout;
							File outF;
							String ffilename = f.fileName.replaceAll("/", "_");
							if(fmtFlags.contains(D64Mod.D64FormatFlag.PRGSEQ)
							&&(f.fileType!=null))
								ffilename += "."+f.fileType.name().toLowerCase();
							if(localFileF.isDirectory())
								outF = new File(localFileF,ffilename);
							else
								outF = localFileF;
							fout=new FileOutputStream(outF);
							fout.write(fileData);
							fout.close();
							System.out.println(fileData.length+" bytes written to "+outF.getAbsolutePath());
						} catch (final Exception e) {
							imageError(e.getMessage(),imageFiles.size()>0);
							continue;
						}
					}
				}
				break;
			}
			case LYNX:
			{
				final String finalDirName = localFileStr.replaceAll("\\*", imageF.getName());
				File localFileF;
				if(new File(finalDirName).isAbsolute())
					localFileF=new File(finalDirName);
				else
					localFileF=new File(imageF.getParent(),finalDirName);
				if(localFileF.isDirectory())
					localFileF = new File(localFileF, imageF.getName().split("\\.(?=[^\\.]+$)")[0]+".lnx");
				FileOutputStream fout;
				try
				{
					fout = new FileOutputStream(localFileF);
					if(imageFileStr.equalsIgnoreCase("all"))
					{
						final int written = D64Mod.writeLNX(files, fout);
						System.out.println(written+" bytes written to "+localFileF.getAbsolutePath());
					}
					else
					{
						final int written = D64Mod.writeLNX(fileList, fout);
						System.out.println(written+" bytes written to "+localFileF.getAbsolutePath());
					}
				}
				catch(final IOException ioe)
				{
					System.out.println("For file: "+localFileF.getAbsolutePath()+": "+ioe.getMessage());
				}
				break;
			}
			case BAM:
			{
				final Set<TrackSec> used = new TreeSet<TrackSec>();
				final Map<String,Set<TrackSec>> qmap = new HashMap<String,Set<TrackSec>>();
				for(final FileInfo f : files)
				{
					final TreeSet<TrackSec> qset=new TreeSet<TrackSec>();
					qmap.put(f.filePath, qset);
					for(final TrackSec s : f.tracksNSecs)
					{
						if(!used.contains(s))
							used.add(s);
						if(!qset.contains(s))
							qset.add(s);
					}
				}
				try
				{
					final Set<TrackSec> unbammed=new TreeSet<TrackSec>();
					final Set<TrackSec> overbammed=new TreeSet<TrackSec>();
					switch(bamAction)
					{
					case CHECK:
					{
						final int[] lastT={1};
						System.out.print("1  : ");
						disk.bamPeruse(new BAMBack()
						{
							@Override
							public boolean call(final short t, final short s, final boolean set,
									final BAMInfo curBAM, final short bamByteOffset,
									final short sumBamByteOffset, final short bamMask)
							{

								final TrackSec ts = TrackSec.valueOf(t,  s);
								if(t != lastT[0])
								{
									lastT[0]=t;
									System.out.println("");
									final String tsstr=Integer.toString(t);
									System.out.print(tsstr+spaces.substring(0,3-tsstr.length())+": ");
								}
								if(set)
								{
									if(used.contains(ts))
									{
										System.out.print("0");
										unbammed.add(ts);
									}
									else
										System.out.print("o");
								}
								else
								{
									if(used.contains(ts))
										System.out.print("x");
									else
									{
										overbammed.add(ts);
										System.out.print("#");
									}
								}
								return false;
							}

						});
						System.out.println("");
						if((unbammed.size()>0)||(overbammed.size()>0))
						{
							System.err.println("Not all sectors matched BAM allocation. "
											+ "0=used, but not marked in bam.  "
											+ "#=UNUSED, but marked used in BAM.");
							System.out.println("Under-bammed: "+unbammed.size());
							System.out.println("Over-bammed : "+overbammed.size());
							if(unbammed.size()>0)
							{
								System.err.println("Un-Bammed files:");
								final Set<String> unbammedS=new HashSet<String>();
								for(final TrackSec I : unbammed)
									for(final String fs : qmap.keySet())
										if(qmap.get(fs).contains(I))
											unbammedS.add(fs);
								for(final String path : unbammedS)
								{
									System.err.print(path);
									final Set<TrackSec> allF=qmap.get(path);
									int numMissing=0;
									for(final TrackSec I : allF)
										if(!unbammed.contains(I))
											numMissing++;
									if(numMissing==0)
										System.err.println(" (total)");
									else
										System.err.println(" (partial "+(int)Math.round(numMissing/(double)allF.size()*100.0)+"%)");
								}
							}
							imageError("",imageFiles.size()>0);
							continue;
						}
						break;
					}
					case ALLOC:
					{
						System.out.print("Allocation need check...");
						disk.bamPeruse(new BAMBack()
						{
							@Override
							public boolean call(final short t, final short s, final boolean set,
									final BAMInfo curBAM, final short bamByteOffset,
									final short sumBamByteOffset, final short bamMask)
							{

								final TrackSec ts = TrackSec.valueOf(t,s);
								if(set)
								{
									if(used.contains(ts))
									{
										final byte[] bamSector = diskBytes[curBAM.track][curBAM.sector];
										bamSector[bamByteOffset] = (byte)(bamSector[bamByteOffset] & (255-bamMask));
										rewriteD64[0]=true;
										unbammed.add(ts);
									}
								}
								else
								{
									if(!used.contains(ts))
										overbammed.add(ts);
								}
								return false;
							}
						});
						System.out.println(unbammed.size()+" sectors allocated.");
						break;
					}
					case FREE:
					{
						System.out.print("De-Allocation need check...");
						disk.bamPeruse(new BAMBack()
						{
							@Override
							public boolean call(final short t, final short s, final boolean set,
									final BAMInfo curBAM, final short bamByteOffset,
									final short sumBamByteOffset, final short bamMask)
							{

								final TrackSec ts = TrackSec.valueOf(t, s);
								if(set)
								{
									if(used.contains(ts))
									{
										unbammed.add(ts);
									}
								}
								else
								{
									if(!used.contains(ts))
									{
										final byte[] bamSector = diskBytes[curBAM.track][curBAM.sector];
										bamSector[bamByteOffset] = (byte)(bamSector[bamByteOffset] | bamMask);
										rewriteD64[0]=true;
										overbammed.add(ts);
									}
								}
								return false;
							}
						});
						System.out.println(overbammed.size()+" sectors freed.");
						break;
					}
					}
				} catch (final IOException e) {
					imageError(e.getMessage(),imageFiles.size()>0);
					continue;
				}
				break;
			}
			case INSERT:
			{
				int x=localFileStr.lastIndexOf('.');
				File localFileF;
				FileType cbmtype = FileType.PRG;
				if(x>=0)
				{
					final String ext=localFileStr.substring(x+1);
					try
					{
						cbmtype = FileType.valueOf(ext.toUpperCase().trim());
						localFileF=new File(localFileStr);
						if(!localFileF.exists())
							localFileF=new File(localFileStr.substring(0,x));
					}
					catch(final Exception e)
					{
						localFileF=new File(localFileStr);
					}
				}
				else
					localFileF= new File(localFileStr);
				if(!localFileF.exists())
				{
					imageError("File not found: "+localFileStr,imageFiles.size()>0);
					continue;
				}
				final byte[] fileData = new byte[(int)localFileF.length()];
				try
				{
					final FileInputStream fin = new FileInputStream(localFileF);
					int totalBytesRead = 0;
					while(totalBytesRead < localFileF.length())
					{
						final int bytesRead = fin.read(fileData,totalBytesRead,(int)(localFileF.length()-totalBytesRead));
						if(bytesRead >=0)
							totalBytesRead+=bytesRead;
					}
					fin.close();
				} catch (final Exception e) {
					imageError(e.getMessage(),imageFiles.size()>0);
					continue;
				}
				FileInfo targetDir = disk.findFile("/",false,parseFlags);
				String targetFileName = null;
				if((file != null)
				&&((file.fileType==FileType.DIR)||(file.fileType==FileType.CBM)))
				{
					targetDir = file;
					int len=16;
					if(localFileF.getName().length()<16)
						len=localFileF.getName().length();
					targetFileName = localFileF.getName().substring(0,len);
				}
				else
				if(file !=null)
				{
					targetFileName = file.fileName;
					if(file.parentF != null)
						targetDir = file.parentF;
					try
					{
						System.out.println("Removing old "+file.filePath);
						disk.scratchFile(file);
					}
					catch(final IOException e)
					{
						imageError(e.getMessage(),imageFiles.size()>0);
						continue;
					}
				}
				else
				{
					x=imageFileStr.lastIndexOf('/');
					while(x>=0)
					{
						FileInfo f=disk.findFile(imageFileStr.substring(0, x), false, parseFlags);
						if(f==null)
							f=disk.findFile(imageFileStr.substring(0, x), true, parseFlags);
						if((f!=null)
						&&((f.fileType==FileType.DIR)||(f.fileType==FileType.CBM)))
						{
							targetDir=f;
							targetFileName=imageFileStr.substring(x+1);
							if(targetFileName.length()>16)
								targetFileName=targetFileName.trim();
							if(targetFileName.length()>16)
								targetFileName=targetFileName.substring(0,16).trim();
							break;
						}
						else
						if(f!=null)
						{
							targetFileName=imageFileStr;
							if(targetFileName.length()>16)
								targetFileName=targetFileName.trim();
							if(targetFileName.length()>16)
								targetFileName=targetFileName.substring(0,16).trim();
							break;
						}
						if(x<1)
							break;
						x=imageFileStr.lastIndexOf('/',x-1);
					}
					if(targetFileName == null)
					{
						targetFileName=imageFileStr;
						if(targetFileName.length()>16)
							targetFileName=targetFileName.trim();
						if(targetFileName.length()>16)
							targetFileName = targetFileName.substring(0,16).trim();
					}
					if(targetFileName.length()==0)
					{
						targetFileName = localFileF.getName();
						if(targetFileName.length()>16)
							targetFileName=targetFileName.trim();
						if(targetFileName.length()>16)
							targetFileName = targetFileName.substring(0,16).trim();
					}
				}
				try
				{
					if(disk.insertFile(targetDir, targetFileName, fileData, cbmtype))
						rewriteD64[0] = true;
				}
				catch(final IOException e)
				{
					imageError(e.getMessage(),imageFiles.size()>0);
				}
				break;
			}
			case FIX:
				System.out.println("Not yet implemented.");
				break;
			case CHECK:
			{
				final Set<TrackSec> allocedts = new TreeSet<TrackSec>();
				if(disk.getType() != ImageType.T64)
				{
					try
					{
						disk.bamPeruse(new BAMBack()
						{
							@Override
							public boolean call(final short t, final short s, final boolean set,
									final BAMInfo curBAM, final short bamByteOffset,
									final short sumBamByteOffset, final short bamMask)
							{
								if(!set)
									allocedts.add(TrackSec.valueOf(t, s));
								return false;
							}
						});
					}
					catch (final IOException e)
					{
						e.printStackTrace();
					}
				}
				for(final FileInfo f : files)
				{
					if(f.filePath.equals("*BAM*")||f.filePath.equals("/"))
						continue;
					if(f.tracksNSecs.size() != f.feblocks)
						System.out.println(f.filePath+" has incorrect block size (fixable)");
					for(final TrackSec ts : f.tracksNSecs)
					{
						if(!allocedts.contains(ts))
						{
							System.out.println(f.filePath+" has un-allocated blocks (fixable)");
							break;
						}
					}
					for(final FileInfo f2 : files)
					{
						if(f != f2)
						{
							int numFound = 0;
							for(final TrackSec ts : f.tracksNSecs)
							{
								if(f2.tracksNSecs.contains(ts))
									numFound++;
							}
							if(numFound == f.tracksNSecs.size())
								System.out.println(f.filePath+" cross links to inside "+f2.filePath);
							else
							{
								int numFound2 = 0;
								for(final TrackSec ts : f2.tracksNSecs)
								{
									if(f.tracksNSecs.contains(ts))
										numFound2++;
								}
								if(numFound2 == f2.tracksNSecs.size())
									System.out.println(f.filePath+" cross links to contain all of "+f2.filePath);
								else
								if(numFound > 0)
									System.out.println(f.filePath+" cross links with "+f2.filePath);
								else
								if(numFound2 > 0)
									System.out.println(f.filePath+" cross links with "+f2.filePath);
							}

						}
					}
				}
				break;
			}
			case LIST:
				if(imageFileStr.equalsIgnoreCase("all"))
				{
					for(final FileInfo f : files)
						System.out.println(f.filePath+","+f.fileType.toString().toLowerCase().charAt(0));
				}
				else
				{
					for(final FileInfo f : fileList)
						System.out.println(f.filePath+","+f.fileType.toString().toLowerCase().charAt(0));
				}
				break;
			case DIR:
			{
				Collection<FileInfo> fs = files;
				if(!imageFileStr.equalsIgnoreCase("all"))
					fs = fileList;
				for(final FileInfo f : fs)
				{
					if(f.fileName.equals("*BAM*") || f.fileName.equals("/"))
						continue;
					final StringBuilder ln=new StringBuilder("");
					ln.append(f.feblocks);
					while(ln.length()<5)
						ln.append(" ");
					ln.append("\"").append(f.fileName).append("\"");
					while(ln.length()<24)
						ln.append(" ");
					ln.append(f.fileType.toString().toLowerCase());
					System.out.println(ln.toString());
				}
				break;
			}
			}
			if(rewriteD64[0])
			{
				try
				{
					final OutputStream fout = imageF.createOutputStream();
					fout.write(disk.getFlatBytes());
					fout.close();
				} catch (final Exception e) {
					imageError(e.getMessage(),imageFiles.size()>0);
					continue;
				}
			}
		}
		System.exit(0);
	}
}
