CURDIR := $(shell pwd)
BUILD  := $(CURDIR)/build
MUSL   := $(BUILD)/musl

CC     := gcc
CFLAGS := -std=c11 -Wall -Wextra -Werror -g -O2
SRCS   := $(wildcard src/*.c)

.PHONY: all musl ktc ktc-kt test test-ktc test-ktc-kt test-llvm test-llvm-kt clean

all: musl ktc

# --- Self-hosted compiler (ktc-kt) ---
KT_SRCS   := $(wildcard kt-src/*.kt)
KT_BUILD  := $(BUILD)/kt-src
KT_OBJS   := $(patsubst kt-src/%.kt,$(KT_BUILD)/%.o,$(KT_SRCS))
KT_RT_OBJ := $(KT_BUILD)/runtime.o

ktc-kt: $(KT_BUILD)/ktc-kt

$(KT_BUILD)/%.s: kt-src/%.kt $(BUILD)/ktc
	@mkdir -p $(KT_BUILD)
	$(BUILD)/ktc $< -o $@

$(KT_BUILD)/%.o: $(KT_BUILD)/%.s
	as --64 -o $@ $<

$(KT_RT_OBJ): kt-src/runtime.c
	@mkdir -p $(KT_BUILD)
	$(CC) -c -o $@ $<

$(KT_BUILD)/ktc-kt: $(KT_OBJS) $(KT_RT_OBJ) $(MUSL)/lib/libc.a
	ld -static -o $@ \
		$(MUSL)/lib/crt1.o $(MUSL)/lib/crti.o \
		$(KT_OBJS) $(KT_RT_OBJ) \
		--start-group $(MUSL)/lib/libc.a --end-group \
		$(MUSL)/lib/crtn.o

musl: $(MUSL)/lib/libc.a

$(MUSL)/lib/libc.a:
	@mkdir -p $(BUILD)
	cd musl && ./configure --prefix=$(MUSL) --disable-shared CFLAGS=-fPIC && $(MAKE) -j$$(nproc) && $(MAKE) install

ktc: $(BUILD)/ktc

$(BUILD)/ktc: $(SRCS)
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -o $@ $(SRCS)

test: test-ktc test-ktc-kt

TESTS := $(wildcard tests/*.kt)
MUSL_LINK = $(MUSL)/lib/crt1.o $(MUSL)/lib/crti.o

define RUN_TESTS
	@echo "=== Testing with $(1) ==="
	@pass=0; fail=0; \
	for f in $(TESTS); do \
		base=$$(basename "$$f" .kt); \
		expect_fail=0; \
		case "$$base" in dangling) expect_fail=1;; esac; \
		if $(2) "$$f" -o $(3)/test_$$base.s --skip-microparse 2>&1; then \
			as --64 -o $(3)/test_$$base.o $(3)/test_$$base.s && \
			ld -static -o $(3)/test_$$base \
				$(MUSL_LINK) $(3)/test_$$base.o \
				--start-group $(MUSL)/lib/libc.a --end-group \
				$(MUSL)/lib/crtn.o && \
			if timeout 5 $(3)/test_$$base >/dev/null 2>&1; then \
				echo "  PASS  $$base"; pass=$$((pass+1)); \
			else \
				case "$$base" in overflow|smash) echo "  PASS  $$base (expected crash)"; pass=$$((pass+1));; \
				*) echo "  FAIL  $$base (runtime)"; fail=$$((fail+1));; esac; \
			fi; \
		else \
			if [ $$expect_fail -eq 1 ]; then \
				echo "  PASS  $$base (expected compile error)"; pass=$$((pass+1)); \
			else \
				echo "  FAIL  $$base (compile)"; fail=$$((fail+1)); \
			fi; \
		fi; \
	done; \
	echo "$(1): $$pass passed, $$fail failed"; \
	[ $$fail -eq 0 ]
endef

# --- Tests with ktc (C compiler) ---
test-ktc: ktc musl
	$(call RUN_TESTS,ktc,$(BUILD)/ktc,$(BUILD))

# --- Tests with ktc-kt (self-hosted compiler) ---
test-ktc-kt: ktc-kt musl
	$(call RUN_TESTS,ktc-kt,$(KT_BUILD)/ktc-kt,$(KT_BUILD))

# --- Tests with ktc --emit-llvm (LLVM backend) ---
test-llvm: ktc musl
	@echo "=== Testing with ktc --emit-llvm ==="
	@pass=0; fail=0; \
	for f in $(TESTS); do \
		base=$$(basename "$$f" .kt); \
		expect_fail=0; \
		case "$$base" in dangling) expect_fail=1;; esac; \
		if $(BUILD)/ktc "$$f" -o $(BUILD)/test_llvm_$$base.ll --emit-llvm --skip-microparse 2>&1; then \
			if llc-18 $(BUILD)/test_llvm_$$base.ll -o $(BUILD)/test_llvm_$$base.s 2>&1 && \
			   as --64 -o $(BUILD)/test_llvm_$$base.o $(BUILD)/test_llvm_$$base.s && \
			   ld -static -o $(BUILD)/test_llvm_$$base \
				$(MUSL_LINK) $(BUILD)/test_llvm_$$base.o \
				--start-group $(MUSL)/lib/libc.a --end-group \
				$(MUSL)/lib/crtn.o; then \
				if timeout 5 $(BUILD)/test_llvm_$$base >/dev/null 2>&1; then \
					echo "  PASS  $$base"; pass=$$((pass+1)); \
				else \
					case "$$base" in overflow|smash) echo "  PASS  $$base (expected crash)"; pass=$$((pass+1));; \
					*) echo "  FAIL  $$base (runtime)"; fail=$$((fail+1));; esac; \
				fi; \
			else \
				echo "  FAIL  $$base (llc/link)"; fail=$$((fail+1)); \
			fi; \
		else \
			if [ $$expect_fail -eq 1 ]; then \
				echo "  PASS  $$base (expected compile error)"; pass=$$((pass+1)); \
			else \
				echo "  FAIL  $$base (compile)"; fail=$$((fail+1)); \
			fi; \
		fi; \
	done; \
	echo "ktc --emit-llvm: $$pass passed, $$fail failed"; \
	[ $$fail -eq 0 ]

# --- Tests with ktc-kt --emit-llvm (self-hosted LLVM backend) ---
test-llvm-kt: ktc-kt musl
	@echo "=== Testing with ktc-kt --emit-llvm ==="
	@pass=0; fail=0; \
	for f in $(TESTS); do \
		base=$$(basename "$$f" .kt); \
		expect_fail=0; \
		case "$$base" in dangling) expect_fail=1;; esac; \
		if $(KT_BUILD)/ktc-kt "$$f" -o $(KT_BUILD)/test_llvm_$$base.ll --emit-llvm --skip-microparse 2>&1; then \
			if llc-18 $(KT_BUILD)/test_llvm_$$base.ll -o $(KT_BUILD)/test_llvm_$$base.s 2>&1 && \
			   as --64 -o $(KT_BUILD)/test_llvm_$$base.o $(KT_BUILD)/test_llvm_$$base.s && \
			   ld -static -o $(KT_BUILD)/test_llvm_$$base \
				$(MUSL_LINK) $(KT_BUILD)/test_llvm_$$base.o \
				--start-group $(MUSL)/lib/libc.a --end-group \
				$(MUSL)/lib/crtn.o; then \
				if timeout 5 $(KT_BUILD)/test_llvm_$$base >/dev/null 2>&1; then \
					echo "  PASS  $$base"; pass=$$((pass+1)); \
				else \
					case "$$base" in overflow|smash) echo "  PASS  $$base (expected crash)"; pass=$$((pass+1));; \
					*) echo "  FAIL  $$base (runtime)"; fail=$$((fail+1));; esac; \
				fi; \
			else \
				echo "  FAIL  $$base (llc/link)"; fail=$$((fail+1)); \
			fi; \
		else \
			if [ $$expect_fail -eq 1 ]; then \
				echo "  PASS  $$base (expected compile error)"; pass=$$((pass+1)); \
			else \
				echo "  FAIL  $$base (compile)"; fail=$$((fail+1)); \
			fi; \
		fi; \
	done; \
	echo "ktc-kt --emit-llvm: $$pass passed, $$fail failed"; \
	[ $$fail -eq 0 ]

clean:
	rm -rf $(BUILD)
