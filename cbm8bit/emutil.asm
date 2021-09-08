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
PReadBuf1           jmp ReadBuf1     ; ReadChan to Buffer1 only

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
CBM2Flag            byte 0
Buffer2             byte <Buffer1, >Buffer1 + 1
BufferE             byte <Buffer1, >Buffer1 + 2
; offset here is *+31

;------------------------------------                    
; Pack from file channel -> ?buffer
;------------------------------------                    
ReadChanBuf2        ldx ReadChan        ; set read channel
                    jsr kCHKIN
                    jsr CBM2Fix         ; and set buffer pointer
                    jsr ResetBuffer2ToZP
                    ldy #$00            ; first, fill both buffers with data
_ClrBufLp           jsr kCHRIN          ; from the read channel
                    sta Buffer1,y
                    sta ($fe),y
                    iny 
                    bne _ClrBufLp
                    ldx WriteChan       ; then check rle flag
                    cpx #$ff
                    bne _ReadChanRLE    ; if writechan/rle flag = $ff
                    lda BufferE+1
                    sta EOBufP+1        ; then just exit, as-is
                    lda BufferE
                    sta EOBufP
                    jmp kCLRCHN
                    
_ReadChanRLE        lda #$00            ; since rle flag set, begin compress
                    sta VarOne          ; clear source buffer index
                    sta BytRepCtr       ; clear rep counter
_ReadRLELp          lda #$00
                    sta BytRepCtr       ; rle begin, so clear rep counter
                    ldy VarOne
                    lda Buffer1,y       ; get next byte from src buf
                    ldy #$01
                    sta ($fe),y         ; put 1 byte ahead of tgt buf ptr
                    sty RLECode         ; init rle code to 1 (makes sense)
                    sty VarTwo          ; init rle tgt buf sub-index to 1
                    inc VarOne          ; increment src buf index
                    bne _ReadRLELoop           ; check if src index rolled over
                    jmp _ReadRLEFinished ; and if it did, we're finished
                    
_ReadRLELoop        ldy VarOne          ; Check if src buf index rolled over
                    bne _ReadRLENextB   ; if it did not, keep processing
                    jmp _ReadRLEFinished ; otherwise, we're finished
                    
_ReadRLENextB       inc VarOne          ; increment src index (poss roll-over...)
                    lda Buffer1,y       ; y has prior src index, so get the byte
                    ldy VarTwo          ; now, using tgt buf sub index
                    iny                 ; increment sub-index ptr
                    sta ($fe),y         ; and store byte in tgt buffer
                    dey                 ; now decrement same sub-index
                    cmp ($fe),y         ; and compare with previous byte
                    beq _ReadRLEisRep   ; if same, go handle that
                    lda #$00
                    sta BytRepCtr       ; not same, so clear rep counter
                    lda RLECode         ; NON RPEAT, so now check rle code
                    cmp #$80            ; if was in rep mode
                    bcs _ReadRLEstopRep ; go stop being in repeat mode
                    cmp #$80            ; and if precisely out of bytes
                    beq _ReadRLEstopRep ; yea, stop being in repeat mode also
                    inc VarTwo          ; increment tgt buf sub index
                    inc RLECode         ; we are in non-rep mode, so inc rle
                    jmp _ReadRLELoop    ; and go get another src buf byte
                    
_ReadRLEstopRep     ldy #$00            ; we need to end repeat mode
                    lda RLECode         ; clear rle code
                    sta ($fe),y         ; and i don't get this part
                    dec VarOne          ; go back in src buf to re-read
                    inc VarTwo          ; increment tgt buf sub-index
                    jsr addVarTwoToZP   ; and add it to tgt buf ptr, so perm
                    jmp _ReadRLELp      ; then go start from scratch with rle 
                    
_ReadRLEisRep       inc BytRepCtr       ; byte repeated, so inc rep ctr
                    lda RLECode         ; now check current rlecode
                    cmp #$80            ; if non-rep, but full, just stop
                    beq _ReadRLEstopRep
                    cmp #$81            ; but if not full, stop Normal mode
                    bcc _ReadRLEstopNorm
                    cmp #$ff
                    beq _ReadRLEstopRep
                    inc RLECode
                    jmp _ReadRLELoop
                    
_ReadRLEstopNorm    cmp #$01            ; stop Normal mode, unless just 1 byte
                    bne _ReadRLEstartRep
_ReadRLEdoRep       lda RLECode         ; otherwise, switch to repeat mode
                    clc 
                    adc #$81            ; by adding repeat flag
                    sta RLECode
                    jmp _ReadRLELoop    ; and continue reading src buf

_ReadRLEstartRep    cmp #$02            ; if rlecode is not 2
                    bne _ReadRLEmayRep           ; then go see if switch can happen
                    lda BytRepCtr       ; otherwise it IS 2, so check rep ctr
                    cmp #$02            ; if rep ctr>=2
                    bcs _ReadRLEdoRep   ; .. go into repeat mode
_ReadRLEcontNrm     inc VarTwo          ; if rep ctr <2, move tgt buf sub ptr
                    inc RLECode         ; inc rle code like we are staying nml
                    jmp _ReadRLELoop    ; and go get next start buf byte
                    
_ReadRLEmayRep      lda BytRepCtr       ; maybe switch, so check rep ctr
                    cmp #$02            ; if repeats so far didn't happen
                    bcc _ReadRLEcontNrm ; then go continue being normal
                    dec RLECode         ; otherwise go backwards entirely
                    dec RLECode
                    dec VarTwo
                    dec VarTwo
                    dec VarOne
                    dec VarOne
                    jmp _ReadRLEstopRep    ; now go back and poss stop repeat
_ReadRLEFinished    ldy #$00
                    lda RLECode            ; store final rle code in tgt buf
                    sta ($fe),y
                    inc VarTwo             ; increment sub-tgt buf index
                    jsr addVarTwoToZP      ; and add it to perm tgt buf ptr
                    lda $ff
                    sta EOBufP+1           ; store end of buffer in eobufp
                    lda $fe
                    sta EOBufP
                    jmp kCLRCHN            ; and exit 
                    
                    
;------------------------------------                    
; Write X bytes from Buffer2 -> channel
;------------------------------------                    
WriteBuf2           ldx WriteChan
                    jsr kCHKOUT    ; set output channel
                    jsr CBM2Fix
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

CBM2Fix             lda CBM2Flag
                    bne _CBM2Fix
                    rts
_CBM2Fix            sta $01
                    rts

;------------------------------------                    
; Unpack from file channel -> buffer
;------------------------------------                    
ReadFileBuf1        ldx ReadChan ; read from channel -> Buffer1
                    jsr kCHKIN   ; set input channel to read from
                    ldy #$00      
                    sty Index     ; set pos 0 in Buffer1
                    ldx WriteChan
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
_ReadRLEMOK         ldy RLECode     ; here is the magic
                    bne _ReadRLERep ; in repeat mode so just repeat .a byt
                    beq _ReadRLEByt ; in raw block mode, so more reading 2 do
                    
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
; Read Channel -> Buffer1
;------------------------------------                    
ReadBuf1            ldx ReadChan
                    jsr kCHKIN
                    ldy #$00
_RD1Lp              jsr kCHRIN
                    sta Buffer1,y
                    iny 
                    bne _RD1Lp
                    jmp kCLRCHN
;------------------------------------                    
; Compare Buffer1 & Buffer2
;------------------------------------                    
CompBuf12           jsr CBM2Fix
                    jsr ResetBuffer2ToZP
                    ldy #$00
                    sty VarOne    ; is this a flag?, seems to be a flag...
_cmloop             lda Buffer1,y
                    cmp ($fe),y
                    bne _cmbad
                    iny           ; this might fix comparing stuff
                    bne _cmloop
                    rts
_cmbad              inc VarOne
                    rts

Buffer1             byte 0
