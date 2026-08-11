.PHONY: all jars clean help

ifeq ($(OS),Windows_NT)
CLASSPATH_SEP := ;
else
CLASSPATH_SEP := :
endif

empty :=
space := $(empty) $(empty)

JAVAC ?= javac
JAR ?= jar
BIN_DIR := bin
DEPS := commons-compress-1.12.jar lha-0.9.jar
CLASSPATH := .$(CLASSPATH_SEP)$(subst $(space),$(CLASSPATH_SEP),$(strip $(DEPS)))
JAVA_SOURCES := $(shell find com -name '*.java' -print)
CLASS_FILES := $(JAVA_SOURCES:.java=.class)
JARS := \
	$(BIN_DIR)/CMDHDParser.jar \
	$(BIN_DIR)/D64Compare.jar \
	$(BIN_DIR)/D64Duplifind.jar \
	$(BIN_DIR)/D64FileMatcher.jar \
	$(BIN_DIR)/D64Search.jar \
	$(BIN_DIR)/D64Mod.jar

all: jars

jars: $(JARS)

$(BIN_DIR):
	mkdir -p $(BIN_DIR)

$(BIN_DIR)/.compiled: $(JAVA_SOURCES) | $(BIN_DIR)
	$(JAVAC) --release 8 -g -Xlint:unchecked -cp "$(CLASSPATH)" $(JAVA_SOURCES)
	@touch $@

define build_fat_jar
	@tmpdir=$$(mktemp -d); \
	trap 'rm -rf "$$tmpdir"' EXIT; \
	for dep in $(DEPS); do unzip -oq "$$dep" -d "$$tmpdir"; done; \
	find com -name '*.class' -print | while IFS= read -r class_file; do \
		mkdir -p "$$tmpdir/$$(dirname "$$class_file")"; \
		cp "$$class_file" "$$tmpdir/$$class_file"; \
	done; \
	$(JAR) --create --file "$@" --main-class $(1) -C "$$tmpdir" .
endef

$(BIN_DIR)/CMDHDParser.jar: $(BIN_DIR)/.compiled
	$(call build_fat_jar,com.planet_ink.emutil.CMDHDParser)

$(BIN_DIR)/D64Compare.jar: $(BIN_DIR)/.compiled
	$(call build_fat_jar,com.planet_ink.emutil.D64Compare)

$(BIN_DIR)/D64Duplifind.jar: $(BIN_DIR)/.compiled
	$(call build_fat_jar,com.planet_ink.emutil.D64Duplifind)

$(BIN_DIR)/D64FileMatcher.jar: $(BIN_DIR)/.compiled
	$(call build_fat_jar,com.planet_ink.emutil.D64FileMatcher)

$(BIN_DIR)/D64Search.jar: $(BIN_DIR)/.compiled
	$(call build_fat_jar,com.planet_ink.emutil.D64Search)

$(BIN_DIR)/D64Mod.jar: $(BIN_DIR)/.compiled
	$(call build_fat_jar,com.planet_ink.emutil.D64Mod)

clean:
	find com -name '*.class' -delete
	rm -f $(JARS) $(BIN_DIR)/.compiled

help:
	@printf '%s\n' \
		'make jars   Build all jar files into bin/' \
		'make clean  Remove compiled classes and jars'
