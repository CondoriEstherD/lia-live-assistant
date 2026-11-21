-- Business rule: after Lia shows the garment photo, the buyer has 1 minute to confirm
-- ("Sí, es esta"). The payment QR is created only after that confirmation.
alter table sellers alter column confirm_minutes set default 1;
update sellers set confirm_minutes = 1 where confirm_minutes = 2;
