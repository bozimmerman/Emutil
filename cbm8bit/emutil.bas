!--------------------------------------------------
!- Thursday, June 01, 2017 2:12:04 PM
!- Import of : 
!- c:\dev\emutil\bin\emutil.prg
!- C64, C128, PET, Plus/4
!--------------------------------------------------
5 GOSUB3400:IFP>0THENPOKEP+1,(ML+1536)/256:CLR:GOSUB3400:POKEP,0:CLR:GOSUB3400
10 MV=ML+(4*4):SP$="{space*21}":GOSUB3300
20 SU=8:SD$="0":DU=8:DD$="0":F1$="Single{space*2}":F2$="Normal{space*3}":MN=1
30 PRINT"{clear}{down*5}{ct n}Emutil v3.1":PRINT"Bo Zimmerman":PRINT"Andre Fachat"
40 PRINT"{down}...Planet Ink.":GOSUB50:GOSUB80:GOTO100
50 TI$="000000"
60 IFTI<200THEN60
70 RETURN
80 VM=0:DIM VM$(3):VM$(0)="None":VM$(1)="Auto":VM$(2)="Only":RETURN
100 PRINT"{clear}{down}Emutil Menu":PRINT"[S] Source Device:";SU;", ";SD$
110 PRINT"[D] Destination Device:";DU;", ";DD$
120 PRINT"[F] Format: ";F1$;" / ";F2$
130 PRINT"[U] Unpack an image":PRINT"[P] Pack an image":M1$="{down}{up}"+CHR$(13)
140 PRINT"[V] Validation mode: ";VM$(VM)
150 PRINT"[I] Disk interface":PRINT"[X] Exit to BASIC":MN$="SDFUPVIX":M1=LEN(MN$)
160 GOSUB200:GOTO220
200 PRINT"{home}{down*2}";:FORI=1TOM1:O=0:IFI=MNTHENO=ASC("{reverse on}")
210 PRINT"{right}";CHR$(O);MID$(MN$,I,1);"{reverse off}":NEXTI:RETURN
220 X$=MN$+M1$:GOSUB400
300 IFO=M1+2ANDMN>1THENMN=MN-1:GOTO160
310 IFO=M1+1ANDMN<LEN(MN$)THENMN=MN+1:GOTO160
320 IFO=M1+3THENO=MN
330 IFO>M1THEN220
335 MN=O:GOSUB200:O=MN
340 O2=O:ONOGOTO500,600,700,1000,2000,350,800:END
350 VM=VM+1:IFVM>2THENVM=0
360 GOTO100
400 O=0:GETA$:IFA$=""THEN400
410 A=ASC(A$):FORI=1TOLEN(X$):B=ASC(MID$(X$,I,1)):IFA=BOR(AOR128)=BANDO=0THENO=I
420 NEXTI:IFO=0THEN400
430 RETURN
500 B$="Source ":X=SU:X$=SD$:GOSUB550:SU=X:SD$=X$:GOTO100
550 PRINT"{down}"B$"Unit :"+STR$(X):PRINT"{up}"SPC(14);:OPEN1,0:INPUT#1,A$:CLOSE1
560 PRINT:X=VAL(A$):IFX<7ORX>30THENPRINT"{up*2}";:GOTO550
570 PRINT"{down}"B$"Drive: "+X$:PRINT"{up}"SPC(14);:OPEN1,0:INPUT#1,A$:CLOSE1
580 PRINT:X$=MID$(STR$(VAL(A$)),2):RETURN
600 B$="Dest.{space*2}":X=DU:X$=DD$:GOSUB550:DU=X:DD$=X$:GOTO100
700 PRINT"{down}[S]ingle or [M]ulti-File: {reverse on} {reverse off}{left}";:X$="SM"+CHR$(13):GOSUB400
710 F1$=MID$("Single{space*2}Multiple"+F1$+"{space*3}",((O-1)*8)+1,8):PRINTF1$:PRINT
720 PRINT"[N]ormal or [C]ompressed: {reverse on} {reverse off}{left}";:X$="NC"+CHR$(13):GOSUB400
730 F2$=MID$("Normal{space*4}Compressed"+F2$+"{space*5}",((O-1)*10)+1,10):PRINTF2$:PRINT
740 PRINT"Hit return: {reverse on} {reverse off}";:X$=CHR$(13):GOSUB400:GOTO100
800 D0=SU:IFSU=DUTHEN810
805 PRINT"{down}[S]ource or [D]est.: {reverse on}S{reverse off}{left}";:X$="SD"+CHR$(13):GOSUB400:IFO<>2THEN810
807 PRINT"{reverse on}D{reverse off}";:D0=DU
810 PRINT:PRINT"{down}Disk interface:":OPEN1,0
820 PRINTD0;"{left}>{reverse on} {reverse off}{left}";:CO$=""
830 GETA$:IFA$=""THEN830
840 A=ASC(A$):IFA=13THEN900
850 IFA=20ANDCO$>""THENPRINT" "A$A$"{reverse on} {reverse off}{left}";:CO$=LEFT$(CO$,LEN(CO$)-1):GOTO830
860 IFA>31ANDA<96THENCO$=CO$+A$:PRINTA$;"{reverse on} {reverse off}{left}";:GOTO830
870 IFA>191ANDA<218THENCO$=CO$+A$:PRINTA$;"{reverse on} {reverse off}{left}";:GOTO830
880 GOTO830
900 CLOSE1:PRINT:PRINT"{up}"SP$:PRINT"{up*2}";
905 IFCO$=""THEN100
906 IFLEFT$(CO$,1)="$"THENOPEN2,D0,0,CO$:GOSUB960:CLOSE2:PRINT:GOTO950
910 IFVAL(CO$)THENPD=VAL(CO$):IFPD>7ANDPD<30THEND0=PD:PRINT:PRINT:GOTO950
920 PRINT:OPEN1,D0,15,CO$
930 INPUT#1,E1,E1$,E2,E3:CLOSE1:PRINTE1;",";E1$;",";E2;",";E3;">"
940 GETA$:IFA$=""THEN940
950 PRINT"{up}"SP$"{up}":GOTO820
960 GET#2,A$:GET#2,A$
970 GET#2,A$:GET#2,A$:IFST>0THENX=FRE(0):RETURN
980 GET#2,A$:GET#2,B$:X=ASC(A$+CHR$(0))+256*ASC(B$+CHR$(0)):PRINTX;
990 GET#2,A$:IFA$=""THENPRINTCHR$(13);:GOTO970
992 GETB$:IFB$=" "THENRETURN
995 PRINTA$;:GOTO990
996 OPEN1,DU,0,"$"+DD$+":z=u":FORI=1TO35:GET#1,A$:NEXT:GET#1,B$:CLOSE1
997 X=ASC(A$+CHR$(0))+256*ASC(B$+CHR$(0)):RETURN
1000 GOSUB1900:VL=0:IFF$=""ORLEFT$(SP$,LEN(F$))=F$THEN100
1005 F$=F$+",s,r":OPEN2,DU,15,"i"+DD$+":":OPEN15,SU,15,"i"+SD$+":"
1010 D0=SU:D0$=SD$:GOSUB1800:IFE=0THEN1020
1020 OPEN3,DU,3,"#":V1=0:EF=0:T=1:S=0:TT=0:E=0
1040 IFEFTHENPRINT:PRINT"Done.":GOSUB50:GOTO1600
1050 IFV1THEN1200
1060 EF=0:PRINT:PRINT"{up}";SP$:PRINT"{up}Track";T;" Sector";S;
1070 POKEMV+1,3:IFLEFT$(F2$,1)="N"THENPOKEMV+1,255
1080 POKEMV,1:SYS(ML+(2*3)):EF=ST
1085 IFPEEK(MV)=255THENPRINT"Invalid Archive!":GOTO1700
1090 PRINT#2,"b-p";3;0:POKEMV+1,3:SYS(ML+(3*3)):V1=1
1200 PRINT#2,"u2";3;VAL(DD$);T;S:INPUT#2,E,E$,E1,E2:IFE=0THENV1=0:GOTO1250
1210 IFE<66THENPRINTE,E$,E1,E2:NE=NE+1:V1=0
1250 TT=TT+1:IFTT>200ANDLEFT$(F1$,1)="M"THENGOSUB1800:TT=0:EF=0
1260 IFE<66ANDS<255THENS=S+1:GOTO1040
1270 IFE<70ANDS>0THENT=T+1:S=0:GOTO1040
1280 GOTO1700
1600 PRINT#2,"i"+DD$+":":PRINT#15,"i"+SD$
1700 CLOSE1:CLOSE15:CLOSE2:CLOSE3
1710 PRINT:PRINT"Hit return: {reverse on} {reverse off}";
1720 GETA$:IFA$<>CHR$(13)THEN1720
1730 GOTO100
1800 VL$="":E=0:VL=VL+1:IFLEFT$(F1$,1)="M"THENIW$=MID$(STR$(VL),2)+"-"
1810 IFLEFT$(F1$,1)="S"ORVL=1THEN1850
1820 IFRIGHT$(F$,1)="r"THEN1850
1825 CLOSE1:GOSUB996:IFX>200THEN1850
1830 PRINT:PRINT"Insert disk for volume"+STR$(VL)
1840 GETA$:IFA$=""THEN1840
1850 CLOSE1:OPEN1,D0,D0,D0$+":"+IW$+F$:F=LEN(F$)
1860 INPUT#15,E,E$,E1,E2:IFE=63ANDRIGHT$(F$,1)="r"THENE=0
1865 IFEANDVL>1THEN1830
1867 IF(E=62ORE=64)ANDRIGHT$(F$,3)="s,r"THENF$=LEFT$(F$,F-3)+"p,r":GOTO1850
1870 IFETHENPRINT"Error opening archive.":GOSUB50
1880 RETURN
1900 F$="":IFSU=DUANDSD$=DD$THENPRINT"Drives must differ!":GOSUB50:RETURN
1910 PRINT"{down}Filename: ";:X$=LEFT$(F1$,1):IFX$="M"THENPRINT"?-";
1920 OPEN1,0:INPUT#1,F$:CLOSE1:X=LEN(F$):PRINT
1930 IFX>16OR(X$="M"ANDX>14)THENPRINT"Filename too long!":PRINT"{up*3}";:GOTO1900
1940 RETURN
2000 GOSUB1900:VL=0:IFF$=""THEN100
2005 F$=F$+",s,w":OPEN2,SU,15,"i"+SD$+":":OPEN15,DU,15,"i"+DD$+":"
2010 D0=DU:D0$=DD$:GOSUB1800:IFETHEN1700
2020 OPEN3,SU,3,"#":V1=0:EF=0:T=1:S=0:TT=0:E=0
2040 PRINT#2,"u1";3;VAL(SD$);T;S
2050 EF=0:PRINT:PRINT"{up}";SP$:PRINT"{up}Track";T;" Sector";S;
2060 INPUT#2,E,E$,E1,E2:IFE>=66THEN2110
2065 IFETHENPRINT:PRINTE,E$,E1,E2:PRINT:GOTO2100
2070 POKEMV+1,3:IFLEFT$(F2$,1)="N"THENPOKEMV+1,255
2080 PRINT#2,"b-p";3;0:POKEMV,3:SYS(ML+(0*3))
2085 IFPEEK(MV)=255THENPRINT"Compress Error!":GOTO1700
2100 POKEMV+1,1:SYS(ML+(1*3))
2110 TT=TT+1:IFTT>200ANDLEFT$(F1$,1)="M"THENGOSUB1800:TT=0
2120 IFE<66ANDS<255THENS=S+1:GOTO2040
2130 IFE<70ANDS>0THENT=T+1:S=0:GOTO2040
2140 PRINT:PRINT"{down}Done!":GOTO1700
3000 DATA"embjcaemnpcbemalccemjdccemkhccaaaaaaaaaaaaaaaaaaaakoapcacamgppkaaaca"
3010 DATA"mpppjjlmccjjlmcdminapekobacaoappnaankjceinbgcakjlminbfcaemmmppkjcdif"
3020 DATA"ppkjlmifpokjaainbbcainbhcakjaainbhcakmbbcaljlmcckaabjbpoimbecaimbcca"
3030 DATA"oobbcanaadembkcbkmbbcanaadembkcboobbcaljlmcckmbccamijbpoiinbpopadikj"
3040 DATA"aainbhcaknbecamjialaanmjiapaajoobccaoobecaemgocakaaaknbecajbpomobbca"
3050 DATA"oobccaknbccabigfpoifpoknbdcagfppifppemfbcaoobhcaknbecamjiapanhmjibja"
3060 DATA"akmjpppampoobecaemgocamjabnaapknbecabigjibinbecaemgocalifalgmjacnaba"
3070 DATA"knbhcamjaclaogoobccaoobecaemgocaknbhcamjacjapamobecamobecamobccamobc"
3080 DATA"camobbcamobbcalifamnkaaaknbecajbpooobccaknbccabigfpoifpoknbdcagfppif"
3090 DATA"ppkfppinbgcakfpoinbfcacaedcbemmmppkjcdifppkjlmifpokjaainbccakaaalbpo"
3100 DATA"ogponaacogppinbecamjiblacokaaalbpoogponaacogppkmbccaoobccanjlmccpaad"
3110 DATA"emmhcbmobecanaodkfppmnbgcanaafkfpomnbfcaladklifambknbecadiojiainbeca"
3120 DATA"kaaalbpoogponaacogppkmbccaoobccanjlmccpaademmhcbmobecanaonkfppmnbgca"
3130 DATA"naafkfpomnbfcalaadlifaikgakjppinapcaknbccainbacaknbdcainbgcaknbccain"
3140 DATA"bfcagakobacacamjppkjcdifppkjlmifpokaaalbpocancppogponaacogppkfppmnbg"
3150 DATA"canaafkfpomnbfcajaofemmmppkoapcacamgppkjccifppkjlmifpokobacaoappnaao"
3160 DATA"kaaacampppjjlmccminaphemmmppcampppinbecamjiblacmcampppkaaajbpoogpona"
3170 DATA"acogppmobecanaookfppmjcdnaaekfpomjlmjanipadicammppkjppinapcainbacaga"
3180 DATA"knbecadiojiainbecacampppinbccakaaajbpoogponaacogppmobecanapdkfppmjcd"
3190 DATA"naaekfpomjlmjakanamiemmmppkobacacamjppkaaaljlmcccancppminaphemmmppkc"
3200 DATA"aainbbcalnlmccnnlmcdnaaeoinapfgaoobbcaga"
3205 DATA""
3210 DATA"cddddnldggdffldfddhddfdfdddndlddffddihddpdhgdkhdddhdddddifdijfdfln"
3220 DATA"rddfdhhignddfdhhlddddddefyhifjmmugsdeggngqi"
3230 DATA""
3300 P=ML:RESTORE:IFPEEK(P)=76THENRETURN
3305 PRINT"reading ml data..."
3310 READA$:LA=LEN(A$):IFLA=0THEN3350
3320 FORI=1TOLASTEP2:X=16*(ASC(MID$(A$,I,1))-65)+(ASC(MID$(A$,I+1,1))-65)
3340 POKEP,X:P=P+1:NEXT:GOTO3310
3350 IFML=8192THENRETURN
3360 D=INT((ML-8192)/256):P=ML
3370 READA$:LA=LEN(A$):IFLA=0THENRETURN
3380 FORI=1TOLA:X=ASC(MID$(A$,I,1))-65:P=P+X:Y=PEEK(P):IFY<32ORY>36THENSTOP
3390 POKEP,PEEK(P)+D:NEXTI:GOTO3370
3400 P=PEEK(65532):IFP=226THENML=9216:P=45:RETURN
3410 IFP=22THENML=8192:P=42:RETURN
3420 IFP=61THENML=4864:P=0:BANK15:RETURN
3430 IFP=246THENML=13312:P=45:RETURN
3440 IFP=34THENML=13824:P=45:RETURN
3490 PRINT"unsupported computer.":STOP
