package com.planet_ink.emutil;
import java.io.*;
import java.util.*;
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
public class D64Mod extends D64Base
{
	enum Action
	{
		SCRATCH, EXTRACT, INSERT, BAM, LIST, DIR
	}
	
	enum BamAction
	{
		CHECK, ALLOC, FREE
	}
	
	public static short[] findFreeSector(final byte[][][] diskBytes, IMAGE_TYPE imagetype, int imageFLen, short startTrack, short stopTrack, short dir, Set<Integer> skip) throws IOException
	{
		short track=startTrack;
		short numFree=0;
		while(track!=stopTrack)
		{
			numFree=sectorsFreeOnTrack(diskBytes,imagetype,imageFLen, track);
			if(numFree > 0)
			{
				short interleave=getImageInterleave(imagetype);
				int secsOnTrack=D64Mod.getImageSecsPerTrack(imagetype, track);
				for(short s1=0;s1<interleave;s1++)
					for(short s=s1;s<secsOnTrack;s+=interleave)
					{
						final Integer sint = Integer.valueOf((track<<8)+s);
						if((skip==null)||(!skip.contains(sint)))
						{
							if(!isSectorAllocated(diskBytes,imagetype,imageFLen,track,s))
								return new short[]{track,s};
						}
					}
			}
			track+=dir;
		}
		return null;
	}
	
	public static short[] nextFreeSectorInDirection(final byte[][][] diskBytes, IMAGE_TYPE imagetype, int imageFLen, short dirTrack, short startTrack, short lastTrack, Set<Integer> skip) throws IOException
	{
		if(startTrack == dirTrack)
		{
			short[] tryBelow = nextFreeSectorInDirection(diskBytes,imagetype,imageFLen,dirTrack,(short)(dirTrack-1),lastTrack,skip);
			short[] tryAbove = nextFreeSectorInDirection(diskBytes,imagetype,imageFLen,dirTrack,(short)(dirTrack+1),lastTrack,skip);
			if((tryAbove==null)&&(tryBelow==null))
				return null;
			if((tryAbove==null)&&(tryBelow != null))
				return tryBelow;
			if((tryBelow==null)&&(tryAbove != null))
				return tryAbove;
			int diffBelow = dirTrack-tryBelow[0];
			int diffAbove = tryAbove[0]-dirTrack;
			if(diffBelow>diffAbove)
				return tryAbove;
			else
				return tryBelow;
		}
		else
		{
			short dir=1;
			short stopTrack=(short)(lastTrack+1);
			if(startTrack<dirTrack)
			{
				dir=-1;
				stopTrack=0;
			}
			return findFreeSector(diskBytes,imagetype,imageFLen,(short)(dirTrack+dir),stopTrack,dir,skip);
		}
	}
	
	public static short[] firstFreeSector(final byte[][][] diskBytes, IMAGE_TYPE imagetype, int imageFLen, Set<Integer> skip) throws IOException
	{
		short dirTrack=(short)D64Base.getImageDirTrack(imagetype);
		short lastTrack=(short)D64Base.getImageNumTracks(imagetype, imageFLen);
		if(imagetype == IMAGE_TYPE.D71)
		{
			short[] sector = nextFreeSectorInDirection(diskBytes,imagetype,imageFLen,dirTrack,dirTrack,(short)35,skip);
			if(sector == null)
				sector = nextFreeSectorInDirection(diskBytes,imagetype,imageFLen,(short)53,(short)53,lastTrack,skip);
			return sector;
		}
		else
		if(imagetype == IMAGE_TYPE.D82)
		{
			short[] sector = nextFreeSectorInDirection(diskBytes,imagetype,imageFLen,dirTrack,dirTrack,(short)77,skip);
			if(sector == null)
				sector = nextFreeSectorInDirection(diskBytes,imagetype,imageFLen,(short)116,(short)116,lastTrack,skip);
			return sector;
		}
		else
		{
			return nextFreeSectorInDirection(diskBytes,imagetype,imageFLen,dirTrack,dirTrack,lastTrack,skip);
		}
	}
	
	public static List<short[]> getFreeSectors(final byte[][][] diskBytes, IMAGE_TYPE imagetype, int imageFLen, int numSectors) throws IOException
	{
		List<short[]> list=new ArrayList<short[]>();
		if(totalSectorsFree(diskBytes,imagetype, imageFLen)<numSectors)
			throw new IOException("Not enough free space.");
		short[] ts=null;
		short lastTrack=(short)D64Base.getImageNumTracks(imagetype, imageFLen);
		short dirTrack=(short)D64Base.getImageDirTrack(imagetype);
		HashSet<Integer> skip=new HashSet<Integer>();
		while(list.size()<numSectors)
		{
			if(ts != null)
			{
				short prevTrack=ts[0];
				if(imagetype == IMAGE_TYPE.D71)
				{
					if(prevTrack<=35)
						ts = nextFreeSectorInDirection(diskBytes,imagetype,imageFLen,dirTrack,prevTrack,(short)35,skip);
					else
						ts = nextFreeSectorInDirection(diskBytes,imagetype,imageFLen,(short)53,prevTrack,lastTrack,skip);
					if((ts == null)&&(prevTrack<=35))
						ts = nextFreeSectorInDirection(diskBytes,imagetype,imageFLen,(short)53,(short)53,lastTrack,skip);
				}
				else
				if(imagetype == IMAGE_TYPE.D82)
				{
					if(prevTrack<=35)
						ts = nextFreeSectorInDirection(diskBytes,imagetype,imageFLen,dirTrack,prevTrack,(short)77,skip);
					else
						ts = nextFreeSectorInDirection(diskBytes,imagetype,imageFLen,(short)116,prevTrack,lastTrack,skip);
					if((ts == null)&&(prevTrack<=35))
						ts = nextFreeSectorInDirection(diskBytes,imagetype,imageFLen,(short)116,(short)116,lastTrack,skip);
				}
				else
					ts = nextFreeSectorInDirection(diskBytes,imagetype,imageFLen,dirTrack,prevTrack,lastTrack,skip);
			}
			if(ts == null)
				ts=firstFreeSector(diskBytes,imagetype,imageFLen,skip);
			if(ts != null)
			{
				
				final Integer sint = Integer.valueOf((ts[0]<<8)+ts[1]);
				skip.add(sint);
				list.add(ts);
			}
		}
		
		return list;
	}
	
	protected static short sectorsFreeOnTrack(final byte[][][] diskBytes, IMAGE_TYPE imagetype, int imageFLen, final short track) throws IOException
	{
		final int[] numAllocated = new int[]{0};
		final boolean[] leaveWhenDone=new boolean[]{false};
		D64Mod.bamPeruse(diskBytes, imagetype, imageFLen, new BAMBack(){
			@Override
			public boolean call(int t, int s, boolean set,
					short[] curBAM, short bamByteOffset,
					short sumBamByteOffset, short bamMask) 
			{
				if(t==track)
				{
					leaveWhenDone[0]=true;
					if(!set)
						numAllocated[0]++;
				}
				else
				if(leaveWhenDone[0])
					return true;
				return false;
			}
		});
		return (short)(D64Mod.getImageSecsPerTrack(imagetype, track) - numAllocated[0]);
	}
	
	protected static int totalSectorsFree(final byte[][][] diskBytes, IMAGE_TYPE imagetype, int imageFLen) throws IOException
	{
		final int[] numAllocated = new int[]{0};
		final int[] numFree = new int[]{0};
		final int noTrack = D64Mod.getImageDirTrack(imagetype);
		D64Mod.bamPeruse(diskBytes, imagetype, imageFLen, new BAMBack(){
			@Override
			public boolean call(int t, int s, boolean set,
					short[] curBAM, short bamByteOffset,
					short sumBamByteOffset, short bamMask) 
			{
				if(!set)
					numAllocated[0]++;
				else
				if(t != noTrack)
					numFree[0]++;
				return false;
			}
		});
		return numFree[0];
	}
	
	protected static boolean scratchFile(final byte[][][] diskBytes, final IMAGE_TYPE imagetype, final int imageFLen, FileInfo file) throws IOException
	{
		byte[] dirSector = diskBytes[file.dirLoc[0]][file.dirLoc[1]];
		dirSector[file.dirLoc[2]]=(byte)(0);
		final Set<Integer> tsSet=new HashSet<Integer>();
		for(short[] ts : file.tracksNSecs)
			tsSet.add(Integer.valueOf((ts[0] << 8) + ts[1]));
		
		D64Mod.bamPeruse(diskBytes, imagetype, imageFLen, new BAMBack() 
		{
			@Override
			public boolean call(int t, int s, boolean set,
					short[] curBAM, short bamByteOffset,
					short sumBamByteOffset, short bamMask) 
			{
				
				Integer tsInt=Integer.valueOf((t << 8) + s);
				if(tsSet.contains(tsInt)&&(!set))
				{
					if(sumBamByteOffset>=0)
					{
						try
						{
							int totalSectors=D64Mod.getImageSecsPerTrack(imagetype, t);
							int sectorsFree=D64Mod.sectorsFreeOnTrack(diskBytes, imagetype, imageFLen, (short)t);
							if((sectorsFree<=totalSectors)
							&&(((curBAM[sumBamByteOffset]&0xff)==sectorsFree)))
								curBAM[sumBamByteOffset] = (byte)((curBAM[sumBamByteOffset]&0xff)-1);
						}
						catch(Exception e)
						{
						}
					}
					byte[] bamSector = diskBytes[curBAM[0]][curBAM[1]];
					bamSector[bamByteOffset] = (byte)(bamSector[bamByteOffset] | bamMask);
				}
				return false;
			}
		});
		return true;
	}
	
	protected static short[] findDirectorySlotInSector(byte[] sector, short[] dirts) throws IOException
	{
		for(int i=2;i<256;i+=32)
		{
			if((sector[i]==(byte)128)||(sector[i]&(byte)128)==0)
				return new short[]{dirts[0],dirts[1],(short)i};
		}
		return null;
	}
	
	protected static short[] findDirectorySlot(final byte[][][] diskBytes, final IMAGE_TYPE imagetype, final int imageFLen, final FileInfo parentDir) throws IOException
	{
		short[] dirts = parentDir.tracksNSecs.get(0);
		/*
		if((parentDir.fileType != FileType.CBM)
		&&(imagetype != IMAGE_TYPE.D80)
		&&(imagetype != IMAGE_TYPE.D82)
		&&(dirts[0]!=0)
		&&(dirts[0]<diskBytes.length)
		&&(dirts[1]<diskBytes[dirts[0]].length))
		{
			byte[] sec=diskBytes[dirts[0]][dirts[1]];
			dirts[0]=unsigned(sec[0]);
			dirts[1]=unsigned(sec[1]);
		}
		*/
		byte[] sec=diskBytes[dirts[0]][dirts[1]];
		short[] found=findDirectorySlotInSector(sec,dirts);
		if(found != null)
			return found;
		while(sec[0]!=0)
		{
			dirts[0]=unsigned(sec[0]);
			dirts[1]=unsigned(sec[1]);
			sec=diskBytes[dirts[0]][dirts[1]];
			found=findDirectorySlotInSector(sec,dirts);
			if(found != null)
				return found;
		}
		if(imagetype != IMAGE_TYPE.DNP)
		{
			short track=dirts[0];
			int numFree=sectorsFreeOnTrack(diskBytes,imagetype,imageFLen, track);
			if(numFree < 0)
				throw new IOException("No root dir sectors free?!");
			short interleave=3;
			int secsOnTrack=D64Mod.getImageSecsPerTrack(imagetype, track);
			for(short s1=0;s1<interleave;s1++)
				for(short s=s1;s<secsOnTrack;s+=interleave)
					if(!isSectorAllocated(diskBytes,imagetype,imageFLen,track,s))
					{
						sec[0]=(byte)(track & 0xff);
						sec[1]=(byte)(s & 0xff);
						byte[] newSec = diskBytes[track][s];
						for(int i=2;i<256;i+=32)
							newSec[i]=0;
						List<short[]> allocs=new ArrayList<short[]>();
						allocs.add(new short[]{track,s});
						D64Mod.allocateSectors(diskBytes, imagetype, imageFLen, allocs);
						return new short[]{track,s,2};
					}
			throw new IOException("No free root dir sectors found.");
		}
		else
		{
			short track=dirts[0];
			short[] ts= findFreeSector(diskBytes,imagetype,imageFLen,track,(short)(D64Base.getImageNumTracks(imagetype, imageFLen)+1),(short)1,null);
			if(ts == null)
				ts= findFreeSector(diskBytes,imagetype,imageFLen,(short)1,(short)(D64Base.getImageNumTracks(imagetype, imageFLen)+1),(short)1,null);
			if(ts == null)
				throw new IOException("No sectors found for dir entry.");
			sec[0]=(byte)(ts[0] & 0xff);
			sec[1]=(byte)(ts[1] & 0xff);
			byte[] newSec = diskBytes[ts[0]][ts[1]];
			for(int i=2;i<256;i+=32)
				newSec[i]=0;
			List<short[]> allocs=new ArrayList<short[]>();
			allocs.add(new short[]{ts[0],ts[1]});
			D64Mod.allocateSectors(diskBytes, imagetype, imageFLen, allocs);
			return new short[]{ts[0],ts[1],2};
		}
	}
	
	protected static boolean allocateSectors(final byte[][][] diskBytes, final IMAGE_TYPE imagetype, final int imageFLen, List<short[]> sectors) throws IOException
	{
		
		final HashSet<Integer> secsToDo=new HashSet<Integer>();
		for(short[] sec : sectors)
			secsToDo.add(Integer.valueOf((sec[0] << 8) + sec[1]));
		D64Mod.bamPeruse(diskBytes, imagetype, imageFLen, new BAMBack() 
		{
			@Override
			public boolean call(int t, int s, boolean set,
					short[] curBAM, short bamByteOffset,
					short sumBamByteOffset, short bamMask) 
			{
				
				Integer tsInt=Integer.valueOf((t << 8) + s);
				if(secsToDo.contains(tsInt)&&(set))
				{
					if(sumBamByteOffset>=0)
					{
						try
						{
							int totalSectors=D64Mod.getImageSecsPerTrack(imagetype, t);
							int sectorsFree=D64Mod.sectorsFreeOnTrack(diskBytes, imagetype, imageFLen, (short)t);
							if((sectorsFree<=totalSectors)
							&&(((curBAM[sumBamByteOffset]&0xff)==sectorsFree)))
								curBAM[sumBamByteOffset] = (byte)((curBAM[sumBamByteOffset]&0xff)+1);
						}
						catch(Exception e)
						{
						}
					}
					byte[] bamSector = diskBytes[curBAM[0]][curBAM[1]];
					bamSector[bamByteOffset] = (byte)(bamSector[bamByteOffset] & (255-bamMask));
				}
				return false;
			}
		});
		return true;
	}

	public static FileInfo findFile(final String imageFileStr, List<FileInfo> files, boolean caseInsensitive)
	{
		if(imageFileStr.length()>0)
		{
			if(caseInsensitive)
			{
				for(FileInfo f : files)
				{
					if(f.filePath.equalsIgnoreCase(imageFileStr))
						return f;
				}
			}
			else
			{
				for(FileInfo f : files)
				{
					if(f.filePath.equals(imageFileStr))
						return f;
				}
			}
		}
		return null;
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
			System.out.println("  BAM CHECK");
			System.out.println("  BAM ALLOC (Checks for sectors that need bam alloc)");
			System.out.println("  BAM FREE (Checks for sectors that need bam free)");
			System.out.println("  LIST/DIR <PATH>");
			System.out.println("  LIST/DIR ALL");
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
		IMAGE_TYPE imagetype = getImageTypeAndZipped(imageF);
		if(imagetype == null)
		{
			System.err.println("File is not an image: "+imagePath);
			System.exit(-1);
		}
		Action action = Action.EXTRACT;
		BamAction bamAction = BamAction.CHECK;
		String localFileStr="";
		try
		{
			action=Action.valueOf(actionStr.toUpperCase().trim());
			switch(action)
			{
			case SCRATCH:
				System.err.println("Not implemented");
				System.exit(-1);
				break;
			case INSERT:
				if(args.length<4)
				{
					System.err.println("Missing target file");
					System.exit(-1);
				}
				System.err.println("Not implemented");
				System.exit(-1);
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
			case BAM:
				try
				{
					bamAction=BamAction.valueOf(args[2].toUpperCase().trim());
				}
				catch(Exception e)
				{
					System.err.println("Invalid sub-command");
					System.exit(-1);
				}
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
				imageFileStr = args[2];
				break;
			}
		}
		catch(Exception e)
		{
			System.err.println("Invalid action: "+actionStr);
			System.exit(-1);
		}
		int[] imageFLen=new int[1];
		final byte[][][] diskBytes = getDisk(imagetype,imageF,imageFLen);
		final List<FileInfo> files = getDiskFiles(imageF.getName(),imagetype, diskBytes, imageFLen[0]);
		FileInfo file = findFile(imageFileStr,files,false);
		if(file == null)
			file = findFile(imageFileStr,files,true);
		if((action == Action.LIST)||(action == Action.DIR))
		{
			if((!imageFileStr.equalsIgnoreCase("ALL")) && (file == null))
			{
				System.err.println("No such path exists in image: "+imageFileStr);
				System.exit(-1);
			}
		}
		else
		if((action == Action.INSERT) && (file != null))
		{
			// that's ok.. anything is OK for insert, really.
		}
		else
		if((action != Action.BAM) && (file == null))
		{
			System.err.println("File not found in image: "+imageFileStr);
			System.exit(-1);
		}
		LinkedList<FileInfo> fileList=new LinkedList<FileInfo>();
		if((file != null)&&(action != Action.INSERT))
		{
			fileList.add(file);
			if((file.fileType == FileType.DIR)||(file.fileType == FileType.CBM))
			{
				LinkedList<FileInfo> notdone=new LinkedList<FileInfo>();
				notdone.add(file);
				while(notdone.size()>0)
				{
					FileInfo dir = notdone.removeFirst();
					for(FileInfo i : files)
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
				FileInfo f = fileList.removeLast();
				try
				{
					if(D64Mod.scratchFile(diskBytes, imagetype, imageFLen[0], f))
						System.out.println("Scratched "+f.filePath);
				}
				catch(Exception e)
				{
					System.err.println(e.getMessage());
					System.exit(-1);
				}
			}
			break;
		}
		case EXTRACT:
		{
			int x=localFileStr.lastIndexOf(File.separator);
			File localFileF;
			if(x>0)
				localFileF=new File(new File(localFileStr.substring(0,x)),localFileStr.substring(x+1).replaceAll("/", "_"));
			else
				localFileF=new File(localFileStr.replaceAll("/", "_"));
			if((fileList.size()>1)&&(!localFileF.isDirectory()))
			{
				System.err.println(localFileStr+" needs to be a directory to extract "+fileList.size()+" files.");
				System.exit(-1);
			}
			while(fileList.size()>0)
			{
				FileInfo f = fileList.removeLast();
				try 
				{
					FileOutputStream fout;
					File outF;
					if(localFileF.isDirectory())
						outF = new File(localFileF,f.fileName.replaceAll("/", "_"));
					else
						outF = localFileF;
					fout=new FileOutputStream(outF);
					fout.write(f.data);
					fout.close();
					System.out.println(file.data.length+" bytes written to "+outF.getAbsolutePath());
				} catch (Exception e) {
					System.err.println(e.getMessage());
					System.exit(-1);
				}
			}
			break;
		}
		case BAM:
		{
			final HashSet<Integer> used = new HashSet<Integer>();
			final HashMap<String,HashSet<Integer>> qmap = new HashMap<String,HashSet<Integer>>();
			for(FileInfo f : files)
			{
				HashSet<Integer> qset=new HashSet<Integer>();
				qmap.put(f.filePath, qset);
				for(short[] s : f.tracksNSecs)
				{
					Integer x=Integer.valueOf((s[0] << 8) + s[1]);
					if(!used.contains(x))
						used.add(x);
					if(!qset.contains(x))
						qset.add(x);
				}
			}
			try
			{
				final HashSet<Integer> unbammed=new HashSet<Integer>();
				final HashSet<Integer> overbammed=new HashSet<Integer>();
				switch(bamAction)
				{
				case CHECK:
				{
					final int[] lastT={1};
					System.out.print("1  : ");
					D64Mod.bamPeruse(diskBytes, imagetype, imageFLen[0], new BAMBack(){
						@Override
						public boolean call(int t, int s, boolean set,
								short[] curBAM, short bamByteOffset,
								short sumBamByteOffset, short bamMask) 
						{
							
							if(t != lastT[0])
							{
								lastT[0]=t;
								System.out.println("");
								final String ts=Integer.toString(t);
								System.out.print(ts+spaces.substring(0,3-ts.length())+": ");
							}
							final Integer tsInt=Integer.valueOf((t << 8) + s);
							if(set)
							{
								if(used.contains(tsInt))
								{
									System.out.print("0");
									unbammed.add(tsInt);
								}
								else
									System.out.print("o");
							}
							else
							{
								if(used.contains(tsInt))
									System.out.print("x");
								else
								{
									overbammed.add(tsInt);
									System.out.print("#");
								}
							}
							return false;
						}
						
					});
					System.out.println("");
					if((unbammed.size()>0)||(overbammed.size()>0))
					{
						System.err.println("Not all sectors matched BAM allocation. 0=used, but not marked in bam.  #=UNUSED, but marked used in BAM.");
						if(unbammed.size()>0)
						{
							System.err.println("Un-Bammed files:");
							Set<String> unbammedS=new HashSet<String>();
							for(Integer I : unbammed)
								for(String fs : qmap.keySet())
									if(qmap.get(fs).contains(I))
										unbammedS.add(fs);
							for(String path : unbammedS)
							{
								System.err.print(path);
								HashSet<Integer> allF=qmap.get(path);
								int numMissing=0;
								for(Integer I : allF)
									if(!unbammed.contains(I))
										numMissing++;
								if(numMissing==0)
									System.err.println(" (total)");
								else
									System.err.println(" (partial "+(int)Math.round((double)numMissing/(double)allF.size()*100.0)+"%)");
							}
						}
						System.exit(-1);
					}
					break;
				}
				case ALLOC:
				{
					System.out.print("Allocation need check...");
					D64Mod.bamPeruse(diskBytes, imagetype, imageFLen[0], new BAMBack(){
						@Override
						public boolean call(int t, int s, boolean set,
								short[] curBAM, short bamByteOffset,
								short sumBamByteOffset, short bamMask) 
						{
							
							Integer tsInt=Integer.valueOf((t << 8) + s);
							if(set)
							{
								if(used.contains(tsInt))
								{
									byte[] bamSector = diskBytes[curBAM[0]][curBAM[1]];
									bamSector[bamByteOffset] = (byte)(bamSector[bamByteOffset] & (255-bamMask));
									rewriteD64[0]=true;
									unbammed.add(tsInt);
								}
							}
							else
							{
								if(!used.contains(tsInt))
									overbammed.add(tsInt);
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
					D64Mod.bamPeruse(diskBytes, imagetype, imageFLen[0], new BAMBack(){
						@Override
						public boolean call(int t, int s, boolean set,
								short[] curBAM, short bamByteOffset,
								short sumBamByteOffset, short bamMask) 
						{
							
							Integer tsInt=Integer.valueOf((t << 8) + s);
							if(set)
							{
								if(used.contains(tsInt))
								{
									unbammed.add(tsInt);
								}
							}
							else
							{
								if(!used.contains(tsInt))
								{
									byte[] bamSector = diskBytes[curBAM[0]][curBAM[1]];
									bamSector[bamByteOffset] = (byte)(bamSector[bamByteOffset] | bamMask);
									rewriteD64[0]=true;
									overbammed.add(tsInt);
								}
							}
							return false;
						}
					});
					System.out.println(overbammed.size()+" sectors freed.");
					break;
				}
				}
			} catch (IOException e) {
				System.err.println(e.getMessage());
				System.exit(-1);
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
				String ext=localFileStr.substring(x+1);
				try
				{ 
					cbmtype = FileType.valueOf(ext.toUpperCase().trim()); 
					localFileF=new File(localFileStr);
					if(!localFileF.exists())
						localFileF=new File(localFileStr.substring(0,x));
				} 
				catch(Exception e)
				{
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
			FileInfo targetDir = findFile("/",files,false);
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
					D64Mod.scratchFile(diskBytes, imagetype, imageFLen[0], file);
				}
				catch(IOException e)
				{
					System.err.println(e.getMessage());
					System.exit(-1);
				}
			}
			else
			{
				x=imageFileStr.lastIndexOf('/');
				while(x>=0)
				{
					FileInfo f=D64Mod.findFile(imageFileStr.substring(0, x), files, false);
					if(f==null)
						f=D64Mod.findFile(imageFileStr.substring(0, x), files, false);
					if((f!=null)
					&&((f.fileType==FileType.DIR)||(f.fileType==FileType.CBM)))
					{
						targetDir=f;
						targetFileName=imageFileStr.substring(x+1).substring(0, 16).trim();
						break;
					}
					else
					if(f!=null)
					{
						targetFileName=imageFileStr.substring(0,16).trim();
						break;
					}
					if(x<1)
						break;
					x=imageFileStr.lastIndexOf('/',x-1);
				}
				if(targetFileName == null)
					targetFileName=imageFileStr.substring(0,16).trim();
				if(targetFileName.length()==0)
					targetFileName = localFileF.getName().substring(0,16);
			}
			int sectorsNeeded = (int)Math.round(Math.ceil(fileData.length / 254.0));
			try
			{
				final List<short[]> sectorsToUse = D64Mod.getFreeSectors(diskBytes, imagetype, imageFLen[0], sectorsNeeded);
				if((sectorsToUse==null)||(sectorsToUse.size()<sectorsNeeded))
					throw new IOException("Not enough space on disk for "+localFileF.getAbsolutePath());
				short[] dirSlot = findDirectorySlot(diskBytes, imagetype, imageFLen[0], targetDir);
				int bufDex = 0;
				int secDex = 0;
				while(bufDex < fileData.length)
				{
					if(secDex >= sectorsToUse.size())
						throw new IOException("Not enough sectors found for "+localFileF.getAbsolutePath());
					short[] sec = sectorsToUse.get(secDex++);
					byte[] secBlock = diskBytes[sec[0]][sec[1]];
					int bytesToWrite=254;
					if(fileData.length-bufDex<254)
						bytesToWrite=fileData.length-bufDex;
					Arrays.fill(secBlock, (byte)0);
					for(int i=2;i<2+bytesToWrite;i++)
						secBlock[i]=fileData[bufDex+i-2];
					if(secDex < sectorsToUse.size())
					{
						short[] nextSec = sectorsToUse.get(secDex);
						secBlock[0]=(byte)(nextSec[0] & 0xff);
						secBlock[1]=(byte)(nextSec[1] & 0xff);
					}
					else
					if(fileData.length-bufDex<=254)
					{
						secBlock[0]=0;
						secBlock[1]=(byte)(1+bytesToWrite);
					}
					else
						throw new IOException("Not enough sectors available for "+localFileF.getAbsolutePath());
					bufDex += bytesToWrite;
				}
				if(secDex<sectorsToUse.size())
					throw new IOException("Too many sectors found for "+localFileF.getAbsolutePath());
				byte[] dirSec=diskBytes[dirSlot[0]][dirSlot[1]];
				short dirByte=dirSlot[2];
				switch(cbmtype)
				{
				case CBM:
					dirSec[dirByte]=tobyte(5+128);
					break;
				case DEL:
					dirSec[dirByte]=tobyte(0+128);
					break;
				case DIR:
					dirSec[dirByte]=tobyte(6+128);
					break;
				case PRG:
					dirSec[dirByte]=tobyte(2+128);
					break;
				case REL:
					dirSec[dirByte]=tobyte(4+128);
					break;
				case SEQ:
					dirSec[dirByte]=tobyte(1+128);
					break;
				case USR:
					dirSec[dirByte]=tobyte(3+128);
					break;
				}
				dirSec[dirByte+1]=tobyte(sectorsToUse.get(0)[0]);
				dirSec[dirByte+2]=tobyte(sectorsToUse.get(0)[1]);
				for(int i=3;i<=18;i++)
				{
					int fnoffset=i-3;
					if(fnoffset<targetFileName.length())
						dirSec[dirByte+i]=tobyte(D64Base.convertToPetscii(tobyte(targetFileName.charAt(fnoffset))));
					else
						dirSec[dirByte+i]=tobyte(160);
				}
				for(int i=19;i<=27;i++)
					dirSec[dirByte+i]=0;
				if(imagetype==IMAGE_TYPE.DNP)
				{
					Calendar C=Calendar.getInstance();
					int year=C.get(Calendar.YEAR);
					year-=(int)Math.round(Math.floor(year/100.0))*100;
					dirSec[dirByte+23]=tobyte(year);
					dirSec[dirByte+24]=tobyte(C.get(Calendar.MONTH)+1);
					dirSec[dirByte+25]=tobyte(C.get(Calendar.DAY_OF_MONTH));
					dirSec[dirByte+26]=tobyte(C.get(Calendar.HOUR_OF_DAY));
					dirSec[dirByte+27]=tobyte(C.get(Calendar.MINUTE));
				}
				int szHB = (int)Math.round(Math.floor(sectorsToUse.size() / 256.0));
				int szLB = sectorsToUse.size() - (szHB * 256);
				dirSec[dirByte+28]=tobyte(szLB);
				dirSec[dirByte+29]=tobyte(szHB);
				D64Mod.allocateSectors(diskBytes, imagetype, imageFLen[0], sectorsToUse);
				rewriteD64[0]=true;
			}
			catch(IOException e)
			{
				System.err.println(e.getMessage());
				System.exit(-1);
			}
			break;
		}
		case LIST:
			if(imageFileStr.equalsIgnoreCase("all"))
			{
				for(FileInfo f : files)
					System.out.println(f.filePath+","+f.fileType.toString().toLowerCase().charAt(0));
			}
			else
			{
				for(FileInfo f : fileList)
					System.out.println(f.filePath+","+f.fileType.toString().toLowerCase().charAt(0));
			}
			break;
		case DIR:
		{
			Collection<FileInfo> fs = files;
			if(!imageFileStr.equalsIgnoreCase("all"))
				fs = fileList;
			for(FileInfo f : fs)
			{
				if(f.fileName.equals("*BAM*") || f.fileName.equals("/"))
					continue;
				int blkLen = (int)Math.round(Math.floor(f.size / 254.0));
				StringBuilder ln=new StringBuilder("");
				ln.append(blkLen);
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
				FileOutputStream fout = new FileOutputStream(imageF);
				for(int b1=1;b1<diskBytes.length;b1++)
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
