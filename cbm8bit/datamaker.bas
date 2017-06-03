!--------------------------------------------------
!- Saturday, June 03, 2017 2:28:02 PM
!- Import of : 
!- c:\src\emutil\cbm8bit\datamaker.prg
!- Commodore 64
!--------------------------------------------------
10 P=8192:L=3000
20 PRINTMID$(STR$(L),2);"dA"+CHR$(34);:C=0:Y=0
30 X=PEEK(P):X1=INT(X/16):X2=X-(X1*16):IFX=0THENY=Y+1
40 PRINTCHR$(65+X1)CHR$(65+X2);:P=P+1:C=C+1:IFC<34THEN30
50 PRINTCHR$(34):IFY>11THENEND
60 L=L+10:GOTO20
