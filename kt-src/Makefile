KTC    := ../build/ktc
MUSL   := ../build/musl
BUILD  := ../build/kt-src
AS     := as --64
LD     := ld -static
CC     := gcc

SRCS   := $(wildcard *.kt)
ASM    := $(patsubst %.kt,$(BUILD)/%.s,$(SRCS))
OBJS   := $(patsubst %.kt,$(BUILD)/%.o,$(SRCS))

# Runtime library (C)
RT_OBJ := $(BUILD)/runtime.o

CRT    := $(MUSL)/lib/crt1.o $(MUSL)/lib/crti.o
CRTEND := $(MUSL)/lib/crtn.o
LIBC   := $(MUSL)/lib/libc.a

.PHONY: all clean

all: $(BUILD)/ktc-kt

$(BUILD)/%.s: %.kt types.kth $(KTC)
	@mkdir -p $(BUILD)
	$(KTC) $< -o $@

$(BUILD)/%.o: $(BUILD)/%.s
	$(AS) -o $@ $<

$(RT_OBJ): runtime.c
	@mkdir -p $(BUILD)
	$(CC) -c -O2 -I$(MUSL)/include -o $@ $<

$(BUILD)/ktc-kt: $(OBJS) $(RT_OBJ) $(LIBC)
	$(LD) -o $@ $(CRT) $(OBJS) $(RT_OBJ) --start-group $(LIBC) --end-group $(CRTEND)
	@echo "--- built kt-src/ktc-kt (self-hosted compiler) ---"

clean:
	rm -rf $(BUILD)
