CURDIR := $(shell pwd)
BUILD  := $(CURDIR)/build
MUSL   := $(BUILD)/musl

CC     := gcc
CFLAGS := -std=c11 -Wall -Wextra -Werror -g -O2
SRCS   := $(wildcard src/*.c)

.PHONY: all musl ktc test clean

all: musl ktc

musl: $(MUSL)/lib/libc.a

$(MUSL)/lib/libc.a:
	@mkdir -p $(BUILD)
	cd musl && ./configure --prefix=$(MUSL) --disable-shared CFLAGS=-fPIC && $(MAKE) -j$$(nproc) && $(MAKE) install

ktc: $(BUILD)/ktc

$(BUILD)/ktc: $(SRCS)
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ $(SRCS)

test: ktc musl $(BUILD)/hello
	@echo "--- Running hello ---"
	$(BUILD)/hello
	@echo ""
	@echo "--- Test passed ---"

$(BUILD)/hello: $(BUILD)/hello.o $(MUSL)/lib/libc.a
	ld -static -o $@ \
		$(MUSL)/lib/crt1.o $(MUSL)/lib/crti.o \
		$< \
		--start-group $(MUSL)/lib/libc.a --end-group \
		$(MUSL)/lib/crtn.o

$(BUILD)/hello.o: $(BUILD)/hello.s
	as --64 -o $@ $<

$(BUILD)/hello.s: $(BUILD)/ktc tests/hello.kt
	$(BUILD)/ktc tests/hello.kt -o $@

clean:
	rm -rf $(BUILD)
