* = $1300

kCHKIN = $FFC6
kCHKOUT = $FFC9
kCLRCHN = $FFCC 
kCHRIN = $FFCF
kCHROUT = $FFD2

L1300               jmp L1316
                    
L1303               jmp L14dc
                    
PReadFileBuf1       jmp ReadFileBuf1 ; ReadChan, WriteChan=RLE Flag ($ff=NOT RLE)
                    
PWriteBuf1          jmp WriteBuf1
                    
ReadChan            byte 0
WriteChan           byte 0
L130e               byte 0
L130f               byte 0
L1310               byte 0
RLECode             byte 0
EOBufL              byte 0
EOBufH              byte 0
L1314               byte 0
                    byte 0 

L1316               ldx ReadChan
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
                    sta L130e
                    sta L1314
L134e               lda #$00
                    sta L1314
                    ldy L130e
                    lda Buffer1,y
                    ldy #$01
                    sta ($fe),y
                    sty RLECode
                    sty L130f
                    inc L130e
                    bne L136b
                    jmp L1417
                    
L136b               ldy L130e
                    bne L1373
                    jmp L1417
                    
L1373               inc L130e
                    lda Buffer1,y
                    ldy L130f
                    iny 
                    sta ($fe),y
                    dey 
                    cmp ($fe),y
                    beq L13bc
                    lda #$00
                    sta L1314
                    lda RLECode
                    cmp #$80
                    bcs L139d
                    cmp #$80
                    beq L139d
                    inc L130f
                    inc RLECode
                    jmp L136b
                    
L139d               ldy #$00
                    lda RLECode
                    sta ($fe),y
                    dec L130e
                    inc L130f
                    lda L130f
                    clc 
                    adc $fe
                    sta $fe
                    lda L1310
                    adc $ff
                    sta $ff
                    jmp L134e
                    
L13bc               inc L1314
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
                    lda L1314
                    cmp #$02
                    bcs L13d8
L13f2               inc L130f
                    inc RLECode
                    jmp L136b
                    
L13fb               lda L1314
                    cmp #$02
                    bcc L13f2
                    dec RLECode
                    dec RLECode
                    dec L130f
                    dec L130f
                    dec L130e
                    dec L130e
                    clv 
                    bvc L13e4
L1417               ldy #$00
                    lda RLECode
                    sta ($fe),y
                    inc L130f
                    lda L130f
                    clc 
                    adc $fe
                    sta $fe
                    lda L1310
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
                    sta L130f
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
L1468               ldy L130f
                    inc L130f
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
L149f               ldy L130f
                    inc L130f
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
                    lda L130f
                    sta WriteChan
                    lda L1310
                    sta EOBufH
                    lda L130f
                    sta EOBufL
                    rts 
                    
L14dc               ldx WriteChan
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
                    cmp #$16
                    bne L1551
                    lda $fe
                    cmp #$a4
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
                    sta L130f
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
Buffer1             byte 0
Buffer2 = Buffer1 + 256
BufferE = Buffer2 + 256
