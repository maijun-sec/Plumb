#!/usr/bin/perl
# Repair checks whose visit signature lost the variable name.
# Reads each broken file, finds the loop variable, and patches the visit signature.

use strict;
use warnings;

my $dir = 'src/main/java/org/plumb/style/style';

my @files = qw(
    EnumAbstractMethodCheck EnumIdiomCheck
    ExplicitInitializationCheck FallThroughCheck
    IdempotentOperationsCheck IncompatibleMaskCheck
    InstantiationToGetClassCheck NoEnumTrailingCommaCheck
    ObjectsEqualityCheck OperatorWrapCheck
    ParenPadCheck PublicProtectedFieldInEnumCheck
    QuestionableBooleanAssignmentCheck
    ReachableAssertionCheck RelationalOperatorSymmetryCheck
    RequireThisCheck SeparatorWrapCheck
    SerializableSuperConstructorCheck SignOfAdditionCheck
    SimpleDateFormatWithoutLocaleCheck
    SingleSpaceSeparatorCheck StartWhereGreaterThanEndCheck
    SuspiciousIntegerDivisionCheck
    UnclearedSensitiveInfoCheck UnnecessaryEnumCaseCheck
    UpperEllCheck WhitespaceAfterCheck
    EmptyStaticInitializerCheck EmptyZipEntryCheck
    ModifierOrderCheck RvRemoveRepeatCallsCheck
);

my $fixed = 0;
for my $name (@files) {
    my $path = "$dir/$name.java";
    next unless -f $path;

    open my $fh, '<', $path or die "Cannot read $path: $!";
    local $/;
    my $content = <$fh>;
    close $fh;

    my $orig = $content;

    # Find the visit line
    unless ($content =~ m{
        public\s+void\s+visit\(\s*(?<type>[^,]+?)\s*,\s*(?:StyleContext\s+)?\w+\s*\)\s*\{
    }x) {
        next;
    }
    my $type = $+{type};
    $type =~ s/^\s+|\s+$//g;

    # Find the for-loop variable for this type (within next 15 lines)
    my $loop_var;
    my @lines = split /\n/, $content;
    for my $i (0 .. $#lines) {
        next unless $lines[$i] =~ /public\s+void\s+visit\(/;
        for my $j ($i+1 .. $i+15) {
            last if $j > $#lines;
            if ($lines[$j] =~ /for\s*\(\s*(?:\Q$type\E|\Q$type\E\s+\w+)\s+(\w+)\s*:\s*n\.findAll\(/) {
                $loop_var = $1;
                last;
            }
        }
        last;
    }

    unless ($loop_var) {
        warn "WARN: $name - no loop var found for $type\n";
        next;
    }

    my $old = "visit($type, ctx)";
    my $new = "visit($type, $loop_var)";
    my $count = ($content =~ s/\Q$old\E/$new/g);
    if ($count) {
        open my $out, '>', $path or die "Cannot write $path: $!";
        print $out $content;
        close $out;
        print "Fixed $name: visit($type, $loop_var)\n";
        $fixed++;
    }
}

print "\nTotal fixed: $fixed\n";