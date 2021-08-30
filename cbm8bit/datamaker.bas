!--------------------------------------------------
!- Saturday, June 03, 2017 2:28:02 PM
!- Import of : 
!- c:\src\emutil\cbm8bit\datamaker.prg
!- Commodore 64
!--------------------------------------------------
10 P=8192:L=3000:DIMOF%(100):OX=0
20 IFPEEK(P)<>76THENLOAD"EMUTILASM",8,1
30 PRINTMID$(STR$(L),2);"dA"+CHR$(34);:C=0:Y=0
40 X=PEEK(P):X1=INT(X/16):X2=X-(X1*16):IFX=0THENY=Y+1
50 IFX<>32ANDX<>33ANDX<>34THEN70
60 REM CHECK IF IT NEEDS OFFSET OX
70 PRINTCHR$(65+X1)CHR$(65+X2);:P=P+1:C=C+1:IFC<34THEN40
80 PRINTCHR$(34):IFY>12THEN100
90 L=L+10:GOTO30
100 
