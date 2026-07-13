package com.crm.travelcrm.quotationtemplate.matching;

/** A template together with how well it scored, as produced by {@link TemplateScorer#rank}. */
public record RankedTemplate(TemplateProfile template, MatchScore score) {}