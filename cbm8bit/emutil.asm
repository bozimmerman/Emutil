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
Unknown             byte 0
RLECode             byte 0
EOBufL              byte 0
EOBufH              byte 0
BytRepCtr           byte 0
                    byte 0 

ReadChanBuf2        ldx ReadChan
                    jsr kCHKIN
                    ldy #$00
L131e               jsr kCHRIN
                    sta Buffer1,y
                    sta Buffer2,y
                    iny 
                    bne L131e
                    ldx WriteChan
                    cpx #$ff
                    bne L133e
                    lda #>BufferE
                    sta EOBufH
                    lda #<BufferE
                    sta EOBufL
                    jmp kCLRCHN
                    
L133e               lda #>Buffer2
                    sta $ff
                    lda #<Buffer2
                    sta $fe
                    lda #$00
                    sta VarOne
                    sta BytRepCtr
L134e               lda #$00
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
                    jmp L134e
                    
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
ReadFileBuf1        ldx ReadChan
                    jsr kCHKIN
                    lda #>Buffer1
                    sta $ff
                    lda #<Buffer1
                    sta $fe
                    ldx WriteChan
                    cpx #$ff
                    bne L152b
                    ldy #$00
L151f               jsr kCHRIN
                    sta Buffer1,y
                    iny 
                    bne L151f
                    jmp kCLRCHN
                    
L152b               jsr kCHRIN
                    sta RLECode
                    cmp #$81
                    bcs L1561
L1535               jsr kCHRIN
                    ldy #$00
                    sta ($fe),y
                    inc $fe
                    bne L1542
                    inc $ff
L1542               dec RLECode
                    bne L1535
                    lda $ff
                    cmp #>Buffer2
                    bne L1551
                    lda $fe
                    cmp #<Buffer2
L1551               bcc L152b
                    beq L158d
L1555               jsr kCLRCHN
                    lda #$ff
                    sta ReadChan
                    sta WriteChan
                    rts 
                    
L1561               lda RLECode
                    sec 
                    sbc #$80
                    sta RLECode
                    jsr kCHRIN
                    sta VarTwo
                    ldy #$00
L1572               sta ($fe),y
                    inc $fe
                    bne L157a
                    inc $ff
L157a               dec RLECode
                    bne L1572
                    lda $ff
                    cmp #>Buffer2
                    bne L1589
                    lda $fe
                    cmp #<Buffer2
L1589               bcc L152b
                    bne L1555
L158d               jmp kCLRCHN

;------------------------------------                    
WriteBuf1           ldx WriteChan
                    jsr kCHKOUT
                    ldy #$00
L1598               lda Buffer1,y
                    jsr kCHROUT
                    iny 
                    bne L1598
                    jmp kCLRCHN
;------------------------------------                    
CompBuf12           ldx #$00
                    sta VarOne
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
