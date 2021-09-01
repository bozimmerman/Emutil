* = $2000  ; 2000

kCHKIN = $FFC6
kCHKOUT = $FFC9
kCLRCHN = $FFCC 
kCHRIN = $FFCF
kCHROUT = $FFD2

PReadChanBuf2       jmp ReadChanBuf2 ; ReadChan, WriteChan=RLE Flag, destroy Buf2
PWriteBuf2          jmp WriteBuf2    ; Write X bytes in Buffer2 to channel
PReadFileBuf1       jmp ReadFileBuf1 ; ReadChan, WriteChan=RLE Flag ($ff=NOT RLE)
PWriteBuf1          jmp WriteBuf1    ; Write 256 bytes in Buffer1 to channel
PCompBuf12          jmp CompBuf12    ; Compare Buffer1 to Buffer2, Res in VarOne
; offset here is *+15
ReadChan            byte 0
WriteChan           byte 0
VarOne              byte 0    ; Comp flag in CompBuf12
VarTwo              byte 0, 0
RLECode             byte 0
EOBufP              byte 0, 0 ; Used by WriteBuf2
BytRepCtr           byte 0    ; BytRepCtr in ReadFileBuf1
                    byte 0 
Index               byte 0    ; Write Buffer Index in ReadFileBuf1
Buffer2             byte <Buffer1, >Buffer1 + 1
BufferE             byte <Buffer1, >Buffer1 + 2
; offset here is *+30

;------------------------------------                    
; Pack from file channel -> ?buffer
;------------------------------------                    
ReadChanBuf2        ldx ReadChan
                    jsr kCHKIN
                    jsr ResetBuffer2ToZP
                    ldy #$00
_ClrBufLp           jsr kCHRIN
                    sta Buffer1,y
                    sta ($fe),y
                    iny 
                    bne _ClrBufLp
                    ldx WriteChan
                    cpx #$ff
                    bne _ReadChanRLE
                    lda BufferE+1
                    sta EOBufP+1
                    lda BufferE
                    sta EOBufP
                    jmp kCLRCHN
                    
_ReadChanRLE        lda #$00
                    sta VarOne
                    sta BytRepCtr
_ReadRLELp          lda #$00
                    sta BytRepCtr
                    ldy VarOne
                    lda Buffer1,y
                    ldy #$01
                    sta ($fe),y
                    sty RLECode
                    sty VarTwo
                    inc VarOne
                    bne L136b
                    jmp L1417
                    
L136b               ldy VarOne
                    bne L1373
                    jmp L1417
                    
L1373               inc VarOne
                    lda Buffer1,y
                    ldy VarTwo
                    iny 
                    sta ($fe),y
                    dey 
                    cmp ($fe),y
                    beq L13bc
                    lda #$00
                    sta BytRepCtr
                    lda RLECode
                    cmp #$80
                    bcs L139d
                    cmp #$80
                    beq L139d
                    inc VarTwo
                    inc RLECode
                    jmp L136b
                    
L139d               ldy #$00
                    lda RLECode
                    sta ($fe),y
                    dec VarOne
                    inc VarTwo
                    jsr addVarTwoToZP
                    jmp _ReadRLELp
                    
L13bc               inc BytRepCtr
                    lda RLECode
                    cmp #$80
                    beq L139d
                    cmp #$81
                    bcc L13d4
                    cmp #$ff
                    beq L139d
                    inc RLECode
                    jmp L136b
                    
L13d4               cmp #$01
                    bne L13e7
L13d8               lda RLECode
                    clc 
                    adc #$81
                    sta RLECode
                    jmp L136b
                    
L13e4               clv 
                    bvc L139d
L13e7               cmp #$02
                    bne L13fb
                    lda BytRepCtr
                    cmp #$02
                    bcs L13d8
L13f2               inc VarTwo
                    inc RLECode
                    jmp L136b
                    
L13fb               lda BytRepCtr
                    cmp #$02
                    bcc L13f2
                    dec RLECode
                    dec RLECode
                    dec VarTwo
                    dec VarTwo
                    dec VarOne
                    dec VarOne
                    clv 
                    bvc L13e4
L1417               ldy #$00
                    lda RLECode
                    sta ($fe),y
                    inc VarTwo
                    jsr addVarTwoToZP
                    lda $ff
                    sta EOBufP+1
                    lda $fe
                    sta EOBufP
                    jsr S1440
                    jmp kCLRCHN
                    
S1440               jsr ResetBuffer2ToZP
                    lda #$00
                    sta VarTwo
L144d               ldy #$00
                    lda ($fe),y
                    jsr IncZP
                    sta RLECode
                    cmp #$81
                    bcs L148c
L145e               ldy #$00
                    lda ($fe),y
                    jsr IncZP
                    ldy VarTwo
                    inc VarTwo
                    cmp Buffer1,y
                    beq L1476
                    jmp L14c4
                    
L1476               dec RLECode
                    bne L145e
                    jsr cmpZP2EOBuf
                    bcs L14c3
                    clv 
                    bvc L144d
L148c               lda RLECode
                    sec 
                    sbc #$80
                    sta RLECode
                    ldy #$00
                    lda ($fe),y
                    jsr IncZP
L149f               ldy VarTwo
                    inc VarTwo
                    cmp Buffer1,y
                    beq L14ad
                    jmp L14c4
                    
L14ad               dec RLECode
                    bne L149f
                    jsr cmpZP2EOBuf
                    bcs L14c3
                    clv 
                    bvc L144d
L14c3               rts 
                    
L14c4               lda #$ff
                    sta ReadChan
                    lda VarTwo
                    sta WriteChan
                    lda VarTwo+1
                    sta EOBufP+1
                    lda VarTwo
                    sta EOBufP
                    rts 
                    
;------------------------------------                    
; Write X bytes from Buffer2 -> channel
;------------------------------------                    
WriteBuf2           ldx WriteChan
                    jsr kCHKOUT    ; set output channel
                    jsr ResetBuffer2ToZP
_WB2Loop            ldy #0
                    lda ($fe),y    ; read a byte from Buffer2 
                    jsr kCHROUT    ; write buffer byte to channel
                    jsr IncZP
                    jsr cmpZP2EOBuf
                    bcc _WB2Loop
                    jmp kCLRCHN

ResetBuffer2ToZP    lda Buffer2+1
                    sta $ff
                    lda Buffer2
                    sta $fe        ; reset Buffer2 Pointer for Reading
                    rts

IncZP               inc $fe
                    bne _IncZPX
                    inc $ff
_IncZPX             rts

cmpZP2EOBuf         lda $ff
                    cmp EOBufP+1
                    bne _cmpZP2EOBuf
                    lda $fe
                    cmp EOBufP
_cmpZP2EOBuf        rts

addVarTwoToZP       lda VarTwo
                    clc 
                    adc $fe
                    sta $fe
                    lda VarTwo+1
                    adc $ff
                    sta $ff
                    rts


;------------------------------------                    
; Unpack from file channel -> buffer
;------------------------------------                    
ReadFileBuf1        ldx ReadChan ; read from channel -> Buffer1
                    jsr kCHKIN   ; set input channel to read from
                    ldx WriteChan
                    ldy #$00      
                    sty Index     ; set pos 0 in Buffer1
                    cpx #$ff      ; if write channel not $ff, do unRLE
                    bne _ReadRLE
_ReadImgLp          jsr kCHRIN    ; otherwise, just fill straight bytes
                    sta Buffer1,y ; write byte from CHRIN
                    iny 
                    bne _ReadImgLp; until Buffer1 is full from CHKRIN
_ReadDone           jmp kCLRCHN   ; and then we're done
                    
_ReadRLE            lda #0
                    sta RLECode   ; clear RLE code (0=read, $ff=repeat)
                    jsr kCHRIN    ; begin RLE unpack read
                    cmp #$81      ; $81 or higher is a repeter
                    bcc _ReadRLECtr ; but lower or eq to $80 is not
                    dec RLECode   ; make RLECode $ff for repeats
                    and #$7f      ; clear Rep bit from counter
_ReadRLECtr         sta BytRepCtr
_ReadRLEByt         jsr kCHRIN    ; read the next byte, which always matters
_ReadRLERep         ldy Index     ; .. so read a byte and prepare index
                    sta Buffer1,y ; write CHRIN straight to buffer
                    dec BytRepCtr ; dec rle chats check if out of rle chars
                    bne _ReadRLEMore 
                    inc Index     ; increase target index, and check for err
                    beq _ReadDone ; so Index better have also?
                    bne _ReadRLE  ; need more, so go back
_ReadRLEMore        inc Index     ;
                    bne _ReadRLEMOK ; if Index has more to do, then OK
                    lda #$ff      ; but if it rolled over, there's a prob
                    sta ReadChan  ; set error code 255
                    sta WriteChan
                    jmp kCLRCHN
_ReadRLEMOK         bit RLECode     ; here is the magic
                    bne _ReadRLERep ; 7 bit SET, so just repeat .a byt
                    beq _ReadRLEByt ; 7 bit not set, so more reading 2 do
                    
;------------------------------------                    
; Dump Buffer1 -> Write Channel
;------------------------------------                    
WriteBuf1           ldx WriteChan
                    jsr kCHKOUT
                    ldy #$00
_WB1Lp              lda Buffer1,y
                    jsr kCHROUT
                    iny 
                    bne _WB1Lp
                    jmp kCLRCHN
;------------------------------------                    
; Compare Buffer1 & Buffer2
;------------------------------------                    
CompBuf12           jsr ResetBuffer2ToZP
                    ldy #$00
                    sty VarOne    ; is this a flag?, seems to be a flag...
_cmloop             lda Buffer1,y
                    cmp ($fe),y
                    bne _cmbad
                    inx
                    bne _cmloop
                    rts
_cmbad              inc VarOne
                    rts

Buffer1             byte 0
