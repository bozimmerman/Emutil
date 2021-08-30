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
                    
ReadChan            byte 0
WriteChan           byte 0
VarOne              byte 0    ; Comp flag in CompBuf12
VarTwo              byte 0, 0
RLECode             byte 0
EOBuf               byte 0, 0 ; Used by WriteBuf2
BytRepCtr           byte 0    ; BytRepCtr in ReadFileBuf1
Index               byte 0    ; Write Buffer Index in ReadFileBuf1
                    byte 0 

;------------------------------------                    
; Pack from file channel -> ?buffer
;------------------------------------                    
ReadChanBuf2        ldx ReadChan
                    jsr kCHKIN    ; set the read channel
                    ldy #$00
_ClrBufLp           jsr kCHRIN    ; and fill the buffers
                    sta Buffer1,y
                    sta Buffer2,y
                    iny 
                    bne _ClrBufLp
                    ldx WriteChan  ; check for rle flag
                    cpx #$ff
                    bne _ReadChanRLE ; rle flag set, so go do it
                    lda #>BufferE  ; set end of buffer to buffers end
                    sta EOBuf+1
                    lda #<BufferE  ; because, in this case, they are same
                    sta EOBuf
                    jmp kCLRCHN    ; and exit, as we're done
                    
_ReadChanRLE        jsr _ResetB2W  ; Reset Buffer2 for writing
                    lda #$00       ; clear VarOne
                    sta VarOne
                    sta BytRepCtr
_ReadRLELp          lda #$00
                    sta BytRepCtr
                    ldy VarOne
                    lda Buffer1,y
                    ldy #$01
                    jsr _DoB2Y    ; store char in (Buffer2,y)
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
                    jsr _DoB2Y   ; store char in Buffer2,y
                    dey 
                    jsr _CmpB2Y  ; compare .a to Buffer2,y
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
                    jsr _DoB2Y    ; store in Buffer2,y
                    dec VarOne
                    inc VarTwo
                    jsr _AddV2toB2 ; add VarTwo to Buffer2 ptr
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
                    jmp L139d
L1417               ldy #$00
                    lda RLECode
                    jsr _DoB2Y   ; store in Buffer2,y
                    inc VarTwo
                    jsr _AddV2toB2 ; add VarTwo to Buffer2 ptr
                    lda _DoB2Y+2  ; copy Buffer2Ptr to EOBuf
                    sta EOBuf+1
                    lda _DoB2Y+1  ; finish copy
                    sta EOBuf
                    jsr S1440
                    jmp kCLRCHN
                    
S1440               jsr _ResetB2R
                    lda #$00
                    sta VarTwo
L144d               ldy #$00
                    jsr _DoB2Y ; lda Buffer2,y
                    jsr _IncB2 ; increment Buffer2 ptr
                    sta RLECode
                    cmp #$81
                    bcs L148c
L145e               ldy #$00
                    jsr _DoB2Y ; lda Buffer2,y
                    jsr _IncB2 ; increment Buffer2 ptr
                    ldy VarTwo
                    inc VarTwo
                    cmp Buffer1,y
                    beq L1476
                    jmp L14c4
                    
L1476               dec RLECode
                    bne L145e
                    jsr _CmpB2EO  ; cmp buffer2 to EOBuf
                    bcs L14c3
                    clv 
                    bvc L144d
L148c               lda RLECode
                    sec 
                    sbc #$80
                    sta RLECode
                    ldy #$00
                    jsr _DoB2Y ; lda Buffer2,y
                    jsr _IncB2 ; increment Buffer2 ptr
L149f               ldy VarTwo
                    inc VarTwo
                    cmp Buffer1,y
                    beq L14ad
                    jmp L14c4
                    
L14ad               dec RLECode
                    bne L149f
                    jsr _CmpB2EO  ; cmp buffer2 to EOBuf
                    bcs L14c3
                    clv 
                    bvc L144d
L14c3               rts 
                    
L14c4               lda #$ff
                    sta ReadChan
                    lda VarTwo
                    sta WriteChan
                    lda VarTwo+1
                    sta EOBuf+1
                    lda VarTwo
                    sta EOBuf
                    rts 

_ResetB2R           lda #$89         ; lda absolute,y 
                    jmp _ResetB2
_ResetB2W           lda #$99         ; sta absolute,y
_ResetB2            sta _DoB2Y
                    lda #>Buffer2
                    sta _DoB2Y+2
                    lda #<Buffer2
                    sta _DoB2Y+1
                    rts
_DoB2Y              sta Buffer2,y
                    rts
_IncB2              inc _DoB2Y+1
                    bne _IncB22
                    inc _DoB2Y+2
_IncB22             rts
_CmpB2Y             pha
                    lda _DoB2Y+2
                    sta _CmpB2Y2+2
                    lda _DoB2Y+2
                    sta _CmpB2Y2+2
                    pla
_CmpB2Y2            cmp Buffer2,y
                    rts                    
_CmpB2EO            lda _DoB2Y+2
                    cmp EOBuf+1
                    bne _CmpB2EOX
                    lda _DoB2Y+1
                    cmp EOBuf
_CmpB2EOX           rts
_AddV2toB2          lda VarTwo
                    clc 
                    adc _DoB2Y+1
                    sta _DoB2Y+1
                    lda VarTwo+1
                    adc _DoB2Y+2
                    sta _DoB2Y+2
                    rts

       
;------------------------------------                    
; Write X bytes from Buffer2 -> channel
;------------------------------------                    
WriteBuf2           ldx WriteChan
                    jsr kCHKOUT    ; set output channel
                    lda #>Buffer2
                    sta _WB2Loop+2
                    lda #<Buffer2
                    sta _WB2Loop+1 ; reset Buffer2 Pointer for Reading
_WB2Loop            lda Buffer2    ; read a byte from Buffer2 & inc
                    inc _WB2Loop+1
                    bne _WB2IncX
                    inc _WB2Loop+2
_WB2IncX            jsr kCHROUT    ; write buffer byte to channel
                    lda _WB2Loop+2 ; now see if we reached the end
                    cmp EOBuf+1
                    bne _WB2Comp
                    lda _WB2Loop+1
                    cmp EOBuf
_WB2Comp            bcc _WB2Loop
                    jmp kCLRCHN

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
CompBuf12           ldx #$00
                    stx VarOne    ; is this a flag?, seems to be a flag...
_cmloop             lda Buffer1,x
                    cmp Buffer2,x
                    bne _cmbad
                    inx
                    bne _cmloop
                    rts
_cmbad              inc VarOne
                    rts

Buffer1             byte 0
Buffer2 = Buffer1 + 256
BufferE = Buffer2 + 256
