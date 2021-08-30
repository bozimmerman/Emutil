* = $2000  ; 2000

kCHKIN = $FFC6
kCHKOUT = $FFC9
kCLRCHN = $FFCC 
kCHRIN = $FFCF
kCHROUT = $FFD2

PReadChanBuf2       jmp ReadChanBuf2 ; ReadChan, WriteChan=RLE Flag, destroy Buf2
PWriteBuf2          jmp WriteBuf2
PReadFileBuf1       jmp ReadFileBuf1 ; ReadChan, WriteChan=RLE Flag ($ff=NOT RLE)
PWriteBuf1          jmp WriteBuf1
PCompBuf12          jmp CompBuf12
                    
ReadChan            byte 0
WriteChan           byte 0
VarOne              byte 0
VarTwo              byte 0
Index               byte 0
Unknown             byte 0
RLECode             byte 0
EOBuf               byte 0
BytRepCtr           byte 0
                    byte 0 

;------------------------------------                    
; Pack from file channel -> ?buffer
;------------------------------------                    
ReadChanBuf2        ldx ReadChan
                    jsr kCHKIN    ; set the read channel
_ClrBufLp           jsr kCHRIN    ; and fill the buffers
                    sta Buffer1,y
                    sta Buffer2,y
                    iny 
                    bne _ClrBufLp
                    ldx WriteChan  ; check for rle flag
                    cpx #$ff
                    bne _ReadChanRLE ; rle flag set, so go do it
                    lda #0
                    sta EOBuf    ; otherwise, we are done
                    jmp kCLRCHN
                    
_ReadChanRLE        lda #$00       ; clear VarOne, also $fe=Buffer2
                    sta Index      ; Buffer2 Index
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
                    lda VarTwo
                    clc 
                    adc $fe
                    sta $fe
                    lda Unknown
                    adc $ff
                    sta $ff
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
                    lda VarTwo
                    clc 
                    adc $fe
                    sta $fe
                    lda Unknown
                    adc $ff
                    sta $ff
                    lda $ff
                    sta EOBufH
                    lda $fe
                    sta EOBufL
                    jsr S1440
                    jmp kCLRCHN
                    
S1440               lda #>Buffer2
                    sta $ff
                    lda #<Buffer2
                    sta $fe
                    lda #$00
                    sta VarTwo
L144d               ldy #$00
                    lda ($fe),y
                    inc $fe
                    bne L1457
                    inc $ff
L1457               sta RLECode
                    cmp #$81
                    bcs L148c
L145e               ldy #$00
                    lda ($fe),y
                    inc $fe
                    bne L1468
                    inc $ff
L1468               ldy VarTwo
                    inc VarTwo
                    cmp Buffer1,y
                    beq L1476
                    jmp L14c4
                    
L1476               dec RLECode
                    bne L145e
                    lda $ff
                    cmp EOBufH
                    bne L1487
                    lda $fe
                    cmp EOBufL
L1487               bcs L14c3
                    clv 
                    bvc L144d
L148c               lda RLECode
                    sec 
                    sbc #$80
                    sta RLECode
                    ldy #$00
                    lda ($fe),y
                    inc $fe
                    bne L149f
                    inc $ff
L149f               ldy VarTwo
                    inc VarTwo
                    cmp Buffer1,y
                    beq L14ad
                    jmp L14c4
                    
L14ad               dec RLECode
                    bne L149f
                    lda $ff
                    cmp EOBufH
                    bne L14be
                    lda $fe
                    cmp EOBufL
L14be               bcs L14c3
                    clv 
                    bvc L144d
L14c3               rts 
                    
L14c4               lda #$ff
                    sta ReadChan
                    lda VarTwo
                    sta WriteChan
                    lda Unknown
                    sta EOBufH
                    lda VarTwo
                    sta EOBufL
                    rts 
                    
WriteBuf2           ldx WriteChan
                    jsr kCHKOUT
                    lda #>Buffer2
                    sta $ff
                    lda #<Buffer2
                    sta $fe
L14ea               ldy #$00
                    lda ($fe),y
                    jsr kCHROUT
                    inc $fe
                    bne L14f7
                    inc $ff
L14f7               lda $ff
                    cmp EOBufH
                    bne L1503
                    lda $fe
                    cmp EOBufL
L1503               bcc L14ea
                    jmp kCLRCHN
                    
;------------------------------------                    
; Unpack from file channel -> buffer
;------------------------------------                    
ReadFileBuf1        ldx ReadChan ; read from channel -> Buffer1
                    jsr kCHKIN   ; set input channel to read from
                    ldx WriteChan
                    ldy #$00      
                    sty Index     ; set pos 0 in Buffer1
                    cpx #$ff     ; if write channel not $ff, do unRLE
                    bne _ReadRLE
_ReadImage          jsr kCHRIN    ; otherwise, just fill straight bytes
                    sta Buffer1,y ; write byte from CHRIN
                    iny 
                    bne L151f     ; until Buffer1 is full from CHKRIN
                    jmp kCLRCHN   ; and then we're done
                    
_ReadRLE            jsr kCHRIN    ; begin RLE unpack read
                    sta RLECode   ; save the codee
                    cmp #$81      ; >= $81 means it is a repeated byte
                    bcs _ReadRLERep
_ReadRLEBlk         jsr kCHRIN    ; otherwise, it's just that many to read
                    ldy Index     ; .. so read a byte and prepare index
                    sta Buffer1,y ; write CHRIN straight to buffer
                    inc Index     ; increase target index, and check for err
                    beq _ReadRLEBChk
                    dec RLECode   ; consume a byte ctr
                    bne _ReadRLEBlk ; if there more bytes, go read them
                    lda Index
                    cmp #0        ; see if we're out of target space
                    beq _ReadRLEExit ; yes, so exit happily
                    bne _ReadRLE  ; no, so go read more!

_ReadRLEBChk        dec RLECode    ; reached end of targ buf, so chk RLECode
                    beq _ReadRLEExit ; rlecode also 0, so happy exit
_ReadRLEErr         jsr kCLRCHN    ; begin unhappy error exit
                    lda #$ff
                    sta ReadChan   ; set error code 255
                    sta WriteChan
                    rts 
                    
_ReadRLERep         lda RLECode    ; fix the rle code for repeat bytes
                    sec 
                    sbc #$80       ; by removing the repeat flag
                    sta RLECode
                    jsr kCHRIN     ; now read the One and Only Byte
                    ldy Index
_ReadRLERepLp       sta Buffer1,y
                    iny
                    beq _ReadRLEChk ; if index loops around, check for problem
                    dec RLECode ; otherwise, decrease rle counter
                    bne _ReadRLERepLp ; still more to do, so go write more
                    sty Index ; no more, so save y index for others
                    cpy #0    ; check if out of buffer space
                    bne _ReadRLE ; nope, so go back for more
_ReadRLEExit        jmp kCLRCHN ; out of buffer space, so OK exit
_ReadRLEChk         dec RLECode
                    bne _ReadRLEErr ; still more codes, so error occurred
                    beq _ReadRLEExit ; was no more codes, so exit OK
                    
;------------------------------------                    
; Dump Buffer1 -> Write Channel
;------------------------------------                    
WriteBuf1           ldx WriteChan
                    jsr kCHKOUT
                    ldy #$00
LWB1Lp              lda Buffer1,y
                    jsr kCHROUT
                    iny 
                    bne LWB1Lp
                    jmp kCLRCHN
;------------------------------------                    
; Compare Buffer1 & Buffer2
;------------------------------------                    
CompBuf12           ldx #$00
                    stx VarOne
Lcmloop             lda Buffer1,x
                    cmp Buffer2,x
                    bne Lcmbad
                    inx
                    bne Lcmloop
                    rts
Lcmbad              inc VarOne
                    rts

Buffer1             byte 0
Buffer2 = Buffer1 + 256
BufferE = Buffer2 + 256
