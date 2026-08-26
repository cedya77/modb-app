package io.github.manamiproject.kommand.exceptions

public object UnexpectedUsageOfSudo: RuntimeException("Usage of sudo in CommandLineConfig was set to false, but the provided commands contains sudo.")