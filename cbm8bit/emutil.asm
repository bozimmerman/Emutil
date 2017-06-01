* = 1300

kCHKIN = $FFC6
kCHKOUT = $FFC9
kCLRCHN = $FFCC 
kCHRIN = $FFCF
kCHROUT = $FFD2

L1300               jmp L1316
                    
L1303               jmp L14dc
                    
L1306               jmp L1508
                    
L1309               jmp L1590
                    
L130c               byte 0
L130d               byte 0
L130e               byte 0
L130f               byte 0
L1310               byte 0
L1311               byte 0
L1312               byte 0
L1313               byte 0
L1314               byte 0
L1315               byte 0 
L1316               ldx L130c
                    jsr kCHKIN
                    ldy #$00
L131e               jsr kCHRIN
                    sta Buffer1,y
                    sta Buffer2,y
                    iny 
                    bne L131e
                    ldx L130d
                    cpx #$ff
                    bne L133e
                    lda #$17
                    sta L1313
                    lda #$a4
                    sta L1312
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
                    sty L1311
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
                    lda L1311
                    cmp #$80
                    bcs L139d
                    cmp #$80
                    beq L139d
                    inc L130f
                    inc L1311
                    jmp L136b
                    
L139d               ldy #$00
                    lda L1311
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
                    lda L1311
                    cmp #$80
                    beq L139d
                    cmp #$81
                    bcc L13d4
                    cmp #$ff
                    beq L139d
                    inc L1311
                    jmp L136b
                    
L13d4               cmp #$01
                    bne L13e7
L13d8               lda L1311
                    clc 
                    adc #$81
                    sta L1311
                    jmp L136b
                    
L13e4               clv 
                    bvc L139d
L13e7               cmp #$02
                    bne L13fb
                    lda L1314
                    cmp #$02
                    bcs L13d8
L13f2               inc L130f
                    inc L1311
                    jmp L136b
                    
L13fb               lda L1314
                    cmp #$02
                    bcc L13f2
                    dec L1311
                    dec L1311
                    dec L130f
                    dec L130f
                    dec L130e
                    dec L130e
                    clv 
                    bvc L13e4
L1417               ldy #$00
                    lda L1311
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
                    sta L1313
                    lda $fe
                    sta L1312
                    jsr S1440
                    jmp kCLRCHN
                    
S1440               lda #$16
                    sta $ff
                    lda #$a4
                    sta $fe
                    lda #$00
                    sta L130f
L144d               ldy #$00
                    lda ($fe),y
                    inc $fe
                    bne L1457
                    inc $ff
L1457               sta L1311
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
                    
L1476               dec L1311
                    bne L145e
                    lda $ff
                    cmp L1313
                    bne L1487
                    lda $fe
                    cmp L1312
L1487               bcs L14c3
                    clv 
                    bvc L144d
L148c               lda L1311
                    sec 
                    sbc #$80
                    sta L1311
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
                    
L14ad               dec L1311
                    bne L149f
                    lda $ff
                    cmp L1313
                    bne L14be
                    lda $fe
                    cmp L1312
L14be               bcs L14c3
                    clv 
                    bvc L144d
L14c3               rts 
                    
L14c4               lda #$ff
                    sta L130c
                    lda L130f
                    sta L130d
                    lda L1310
                    sta L1313
                    lda L130f
                    sta L1312
                    rts 
                    
L14dc               ldx L130d
                    jsr kCHKOUT
                    lda #$16
                    sta $ff
                    lda #$a4
                    sta $fe
L14ea               ldy #$00
                    lda ($fe),y
                    jsr kCHROUT
                    inc $fe
                    bne L14f7
                    inc $ff
L14f7               lda $ff
                    cmp L1313
                    bne L1503
                    lda $fe
                    cmp L1312
L1503               bcc L14ea
                    jmp kCLRCHN
                    
L1508               ldx L130c
                    jsr kCHKIN
                    lda #$15
                    sta $ff
                    lda #$a4
                    sta $fe
                    ldx L130d
                    cpx #$ff
                    bne L152b
                    ldy #$00
L151f               jsr kCHRIN
                    sta Buffer1,y
                    iny 
                    bne L151f
                    jmp kCLRCHN
                    
L152b               jsr kCHRIN
                    sta L1311
                    cmp #$81
                    bcs L1561
L1535               jsr kCHRIN
                    ldy #$00
                    sta ($fe),y
                    inc $fe
                    bne L1542
                    inc $ff
L1542               dec L1311
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
                    sta L130c
                    sta L130d
                    rts 
                    
L1561               lda L1311
                    sec 
                    sbc #$80
                    sta L1311
                    jsr kCHRIN
                    sta L130f
                    ldy #$00
L1572               sta ($fe),y
                    inc $fe
                    bne L157a
                    inc $ff
L157a               dec L1311
                    bne L1572
                    lda $ff
                    cmp #$16
                    bne L1589
                    lda $fe
                    cmp #$a4
L1589               bcc L152b
                    bne L1555
L158d               jmp kCLRCHN
                    
L1590               ldx L130d
                    jsr kCHKOUT
                    ldy #$00
L1598               lda Buffer1,y
                    jsr kCHROUT
                    iny 
                    bne L1598
                    jmp kCLRCHN
Buffer1             byte 0
Buffer2 = Buffer1 + 256
BufferE = Buffer2 + 256
