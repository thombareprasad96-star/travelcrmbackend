ALTER TABLE quotations
DROP CONSTRAINT IF EXISTS quotations_template_style_check;

ALTER TABLE quotations
    ADD CONSTRAINT quotations_template_style_check
        CHECK (
            template_style IN (
                               'CLASSIC',
                               'MODERN',
                               'PREMIUM',
                               'LUXURY'
                )
            );